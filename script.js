const videoInput = document.getElementById("videoInput");
const videoPlayer = document.getElementById("videoPlayer");
const emptyState = document.getElementById("emptyState");
const currentTitle = document.getElementById("currentTitle");
const counter = document.getElementById("counter");
const autoplayNext = document.getElementById("autoplayNext");
const previousBtn = document.getElementById("previousBtn");
const playPauseBtn = document.getElementById("playPauseBtn");
const nextBtn = document.getElementById("nextBtn");
const clearBtn = document.getElementById("clearBtn");
const skipMinutes = document.getElementById("skipMinutes");
const skipSeconds = document.getElementById("skipSeconds");
const searchInput = document.getElementById("searchInput");
const playlist = document.getElementById("playlist");
const playlistCount = document.getElementById("playlistCount");

let videos = [];
let currentIndex = -1;
let activeObjectUrl = "";
let skipAppliedForCurrentVideo = false;

function updateMediaSession() {
  if (!("mediaSession" in navigator)) return;

  const currentVideo = videos[currentIndex];
  navigator.mediaSession.metadata = currentVideo
    ? new MediaMetadata({
        title: currentVideo.file.name,
        artist: "IntroSkip Player",
        album: `${currentIndex + 1} of ${videos.length}`,
      })
    : null;
  navigator.mediaSession.playbackState = videoPlayer.paused ? "paused" : "playing";
}

function setupMediaSessionControls() {
  if (!("mediaSession" in navigator)) return;

  const actions = {
    play: () => videoPlayer.play().catch(() => {}),
    pause: () => videoPlayer.pause(),
    previoustrack: playPrevious,
    nexttrack: playNext,
    seekbackward: () => {
      videoPlayer.currentTime = Math.max(0, videoPlayer.currentTime - 10);
    },
    seekforward: () => {
      videoPlayer.currentTime = Math.min(videoPlayer.duration || 0, videoPlayer.currentTime + 10);
    },
  };

  Object.entries(actions).forEach(([action, handler]) => {
    try {
      navigator.mediaSession.setActionHandler(action, handler);
    } catch {
      // Some browsers expose Media Session but support fewer lock-screen actions.
    }
  });
}

function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;

  navigator.serviceWorker.register("service-worker.js").catch(() => {});
}

function getSkipSeconds() {
  const minutes = Math.max(0, Number.parseInt(skipMinutes.value || "0", 10));
  const seconds = Math.min(59, Math.max(0, Number.parseInt(skipSeconds.value || "0", 10)));
  skipMinutes.value = String(minutes);
  skipSeconds.value = String(seconds);
  return minutes * 60 + seconds;
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "Unknown size";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unitIndex = 0;

  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }

  return `${value.toFixed(value >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

function revokeActiveUrl() {
  if (activeObjectUrl) {
    URL.revokeObjectURL(activeObjectUrl);
    activeObjectUrl = "";
  }
}

function updateNowPlaying() {
  const hasVideo = currentIndex >= 0 && videos[currentIndex];
  currentTitle.textContent = hasVideo ? videos[currentIndex].file.name : "Nothing loaded";
  counter.textContent = videos.length ? `${currentIndex + 1} / ${videos.length}` : "0 / 0";
  emptyState.classList.toggle("hidden", hasVideo);
  playPauseBtn.textContent = videoPlayer.paused ? "Play" : "Pause";
  previousBtn.disabled = videos.length === 0;
  nextBtn.disabled = videos.length === 0;
  updateMediaSession();
}

function renderPlaylist() {
  const query = searchInput.value.trim().toLowerCase();
  const visibleVideos = videos
    .map((video, index) => ({ video, index }))
    .filter(({ video }) => video.file.name.toLowerCase().includes(query));

  playlist.innerHTML = "";
  playlistCount.textContent = `${videos.length} ${videos.length === 1 ? "video" : "videos"}`;

  if (videos.length === 0) {
    const item = document.createElement("li");
    item.innerHTML = '<span class="video-meta">Use Add videos to choose files from your phone.</span>';
    playlist.appendChild(item);
    updateNowPlaying();
    return;
  }

  if (visibleVideos.length === 0) {
    const item = document.createElement("li");
    item.innerHTML = '<span class="video-meta">No selected videos match your search.</span>';
    playlist.appendChild(item);
    updateNowPlaying();
    return;
  }

  visibleVideos.forEach(({ video, index }) => {
    const item = document.createElement("li");
    item.classList.toggle("active", index === currentIndex);

    const playButton = document.createElement("button");
    playButton.type = "button";
    playButton.addEventListener("click", () => loadVideo(index, true));

    const name = document.createElement("span");
    name.className = "video-name";
    name.textContent = video.file.name;

    const meta = document.createElement("span");
    meta.className = "video-meta";
    meta.textContent = formatBytes(video.file.size);

    playButton.append(name, meta);

    const removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.className = "remove-video";
    removeButton.setAttribute("aria-label", `Remove ${video.file.name}`);
    removeButton.textContent = "x";
    removeButton.addEventListener("click", () => removeVideo(index));

    item.append(playButton, removeButton);
    playlist.appendChild(item);
  });

  updateNowPlaying();
}

function loadVideo(index, shouldPlay) {
  if (index < 0 || index >= videos.length) return;

  currentIndex = index;
  skipAppliedForCurrentVideo = false;
  revokeActiveUrl();
  activeObjectUrl = URL.createObjectURL(videos[index].file);
  videoPlayer.src = activeObjectUrl;
  videoPlayer.load();
  updateMediaSession();
  renderPlaylist();

  if (shouldPlay) {
    videoPlayer.play().catch(() => {
      playPauseBtn.textContent = "Play";
    });
  }
}

function playNext() {
  if (!videos.length) return;
  const nextIndex = currentIndex + 1 < videos.length ? currentIndex + 1 : 0;
  loadVideo(nextIndex, true);
}

function playPrevious() {
  if (!videos.length) return;
  const previousIndex = currentIndex > 0 ? currentIndex - 1 : videos.length - 1;
  loadVideo(previousIndex, true);
}

function removeVideo(index) {
  const removingCurrent = index === currentIndex;
  videos.splice(index, 1);

  if (!videos.length) {
    currentIndex = -1;
    revokeActiveUrl();
    videoPlayer.removeAttribute("src");
    videoPlayer.load();
  } else if (removingCurrent) {
    loadVideo(Math.min(index, videos.length - 1), false);
    return;
  } else if (index < currentIndex) {
    currentIndex -= 1;
  }

  renderPlaylist();
}

function addVideos(fileList) {
  const selected = Array.from(fileList).filter((file) => file.type.startsWith("video/"));
  const existingKeys = new Set(videos.map(({ file }) => `${file.name}-${file.size}-${file.lastModified}`));

  selected.forEach((file) => {
    const key = `${file.name}-${file.size}-${file.lastModified}`;
    if (!existingKeys.has(key)) {
      videos.push({ file });
      existingKeys.add(key);
    }
  });

  if (currentIndex === -1 && videos.length) {
    loadVideo(0, false);
    return;
  }

  renderPlaylist();
}

videoInput.addEventListener("change", (event) => {
  addVideos(event.target.files);
  videoInput.value = "";
});

videoPlayer.addEventListener("loadedmetadata", () => {
  const skipBy = getSkipSeconds();
  if (skipBy > 0 && videoPlayer.duration > skipBy + 0.5) {
    videoPlayer.currentTime = skipBy;
  }
  skipAppliedForCurrentVideo = true;
});

videoPlayer.addEventListener("timeupdate", () => {
  if (skipAppliedForCurrentVideo || !videoPlayer.duration) return;
  const skipBy = getSkipSeconds();
  if (skipBy > 0 && videoPlayer.duration > skipBy + 0.5) {
    videoPlayer.currentTime = skipBy;
  }
  skipAppliedForCurrentVideo = true;
});

videoPlayer.addEventListener("ended", () => {
  if (autoplayNext.checked) playNext();
});

videoPlayer.addEventListener("play", updateNowPlaying);
videoPlayer.addEventListener("pause", updateNowPlaying);
videoPlayer.addEventListener("durationchange", updateMediaSession);

playPauseBtn.addEventListener("click", () => {
  if (!videos.length) {
    videoInput.click();
    return;
  }

  if (videoPlayer.paused) {
    videoPlayer.play().catch(() => {});
  } else {
    videoPlayer.pause();
  }
});

nextBtn.addEventListener("click", playNext);
previousBtn.addEventListener("click", playPrevious);
searchInput.addEventListener("input", renderPlaylist);

[skipMinutes, skipSeconds].forEach((input) => {
  input.addEventListener("change", () => {
    getSkipSeconds();
    if (videos[currentIndex]) {
      videoPlayer.currentTime = Math.min(getSkipSeconds(), Math.max(0, videoPlayer.duration - 0.5));
    }
  });
});

clearBtn.addEventListener("click", () => {
  videos = [];
  currentIndex = -1;
  revokeActiveUrl();
  videoPlayer.pause();
  videoPlayer.removeAttribute("src");
  videoPlayer.load();
  searchInput.value = "";
  renderPlaylist();
});

renderPlaylist();
setupMediaSessionControls();
registerServiceWorker();
