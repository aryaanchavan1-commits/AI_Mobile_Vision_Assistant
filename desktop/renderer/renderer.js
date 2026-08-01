const el = (id) => document.getElementById(id);
const $log = el("log");
const $status = el("status");
const $chat = el("chat");
const $messages = el("messages");
const $input = el("input");
const $send = el("btn-send");
const history = [];

function setStatus(text, cls) {
  $status.textContent = text;
  $status.className = "status" + (cls ? " " + cls : "");
}

function appendLog(line) {
  $log.textContent += line + "\n";
  $log.scrollTop = $log.scrollHeight;
}

function addMessage(text, who) {
  const div = document.createElement("div");
  div.className = "msg " + who;
  div.textContent = text;
  $messages.appendChild(div);
  $messages.scrollTop = $messages.scrollHeight;
}

function setProgress(label, written, total) {
  const shown = el("progress-label").parentElement;
  shown.classList.remove("hidden");
  el("progress-label").textContent = label;
  const pct = total ? Math.min(100, Math.round((written / total) * 100)) : 0;
  el("progress-bar").style.width = pct + "%";
  el("progress-text").textContent =
    `${(written / 1048576).toFixed(0)} MB / ${(total / 1048576).toFixed(0)} MB (${pct}%)`;
}

async function init() {
  window.arynox.onLog(appendLog);
  window.arynox.onProgress((p) => setProgress(p.label, p.written, p.total));
  window.arynox.onInstallDone(() => {
    el("btn-install").classList.add("hidden");
    el("btn-start").classList.remove("hidden");
    setStatus("models installed", "ready");
  });
  window.arynox.onBrain((text) => {
    for (const line of text.split("\n").filter(Boolean)) {
      if (line.startsWith("Arynox:")) addMessage(line.replace(/^Arynox:\s*/, ""), "arynox");
      else if (line.startsWith("You>") || line.trim() === "" ) { /* skip */ }
      else addMessage(line, "brain");
    }
  });

  for (const line of await window.arynox.logs()) appendLog(line);

  const info = await window.arynox.systemInfo();
  const tier = info.tier;
  el("sysinfo").textContent =
    `OS: ${info.platform}   RAM: ${info.ramGb} GB   CPUs: ${info.cpus}\n` +
    `Auto-selected: ${tier} tier  |  Engine: ${info.providerLabel}\n` +
    `Python: ${info.python || "not found (chat-only mode)"}`;
  setStatus("system detected");

  const installed = info.tier; // tier chosen by this PC
  el("btn-install").classList.remove("hidden");
  el("btn-install").textContent = `Install ${tier} models (auto)`;

  el("btn-install").onclick = async () => {
    el("btn-install").disabled = true;
    setStatus("installing...");
    const r = await window.arynox.install(tier);
    if (!r.ok) {
      setStatus("install failed");
      el("btn-install").disabled = false;
    }
  };
  el("btn-start").onclick = async () => {
    el("btn-start").disabled = true;
    setStatus("starting local AI...");
    const r = await window.arynox.start();
    if (r.ok) {
      setStatus("running", "ready");
      $chat.classList.remove("hidden");
      $input.disabled = false;
      $send.disabled = false;
      el("btn-stop").classList.remove("hidden");
    } else {
      setStatus("start failed");
      el("btn-start").disabled = false;
    }
  };
  el("btn-stop").onclick = async () => {
    await window.arynox.stop();
    setStatus("stopped");
    el("btn-start").disabled = false;
    el("btn-stop").classList.add("hidden");
    $input.disabled = true;
    $send.disabled = true;
  };

  const send = async () => {
    const text = $input.value.trim();
    if (!text) return;
    $input.value = "";
    addMessage(text, "user");
    history.push({ role: "user", content: text });
    const r = await window.arynox.chat(text);
    if (r.ok && r.mode === "llama") addMessage(r.reply, "arynox");
    else if (!r.ok) addMessage("Error: " + r.error, "brain");
  };
  $send.onclick = send;
  $input.addEventListener("keydown", (e) => { if (e.key === "Enter") send(); });
}

init();
