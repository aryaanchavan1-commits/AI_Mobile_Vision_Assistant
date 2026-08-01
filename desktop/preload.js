const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("arynox", {
  systemInfo: () => ipcRenderer.invoke("system-info"),
  install: (tier) => ipcRenderer.invoke("install", tier),
  start: () => ipcRenderer.invoke("start"),
  stop: () => ipcRenderer.invoke("stop"),
  chat: (text) => ipcRenderer.invoke("chat", text),
  logs: () => ipcRenderer.invoke("logs"),
  onLog: (cb) => ipcRenderer.on("log", (_e, line) => cb(line)),
  onProgress: (cb) => ipcRenderer.on("progress", (_e, p) => cb(p)),
  onInstallDone: (cb) => ipcRenderer.on("install-done", (_e, d) => cb(d)),
  onBrain: (cb) => ipcRenderer.on("brain", (_e, text) => cb(text)),
});
