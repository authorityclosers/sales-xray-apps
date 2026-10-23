/*
 * Sales X-Ray for Android: "Calls on this phone".
 * Injected by the app into Sales X-Ray pages only. Lists the call recordings the
 * phone saved by itself and hands the chosen one to the normal New analysis
 * upload, so consent, plan and approval stay exactly as on the web.
 * Nothing leaves the phone until the person taps Analyse.
 * There is no floating button: the page's app menu opens the sheet with
 * window.__sxPhone.openCalls(), which exists only inside the app.
 */
(function () {
  if (window.__sxPhone || typeof window.SXPhone === "undefined") return;

  var pending = {};
  var counter = 0;
  window.SXPhone.onmessage = function (event) {
    var message;
    try {
      message = JSON.parse(event.data);
    } catch (error) {
      return;
    }
    var waiter = pending[message.id];
    if (!waiter) return;
    delete pending[message.id];
    if (message.ok) waiter.resolve(message.result);
    else waiter.reject(new Error(message.error || "phone_error"));
  };
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = "m" + ++counter;
      pending[id] = { resolve: resolve, reject: reject };
      window.SXPhone.postMessage(
        JSON.stringify({ id: id, method: method, args: args || {} }),
      );
    });
  }

  var SENT_KEY = "sx.phone.sent";
  var SEEN_KEY = "sx.phone.seen";
  var PICK_KEY = "sx.phone.pick";
  function load(key, fallback) {
    try {
      return JSON.parse(localStorage.getItem(key) || "null") || fallback;
    } catch (error) {
      return fallback;
    }
  }
  function save(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (error) {}
  }

  var css =
    ".sxp-scrim{position:fixed;inset:0;z-index:2147483001;background:rgba(3,8,18,.55);animation:sxpfade .18s ease-out}" +
    ".sxp-sheet{position:fixed;left:0;right:0;bottom:0;z-index:2147483002;max-height:82vh;display:flex;flex-direction:column;" +
    "border-radius:20px 20px 0 0;background:var(--lx-surface,#0f1b2d);color:var(--lx-ink,#e6edf6);font:14px/1.45 var(--lx-font-ui,system-ui,sans-serif);" +
    "padding-bottom:env(safe-area-inset-bottom,0px);box-shadow:0 -12px 40px rgba(0,0,0,.35);animation:sxpup .22s ease-out}" +
    ".sxp-head{padding:16px 18px 10px;border-bottom:1px solid var(--lx-line,rgba(148,163,184,.18))}" +
    ".sxp-grab{width:40px;height:4px;margin:0 auto 12px;border-radius:2px;background:var(--lx-line-strong,rgba(148,163,184,.4))}" +
    ".sxp-title{display:flex;align-items:center;justify-content:space-between;gap:12px}" +
    ".sxp-title h2{margin:0;font-size:18px;font-weight:700}" +
    ".sxp-close{border:0;background:transparent;color:inherit;font-size:22px;line-height:1;padding:4px 8px}" +
    ".sxp-sub{margin:6px 0 0;color:var(--lx-muted,#94a3b8);font-size:13px}" +
    ".sxp-watch{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:12px;padding:10px 12px;border-radius:12px;" +
    "background:var(--lx-surface-2,rgba(148,163,184,.08))}" +
    ".sxp-watch b{display:block;font-size:13px}.sxp-watch small{color:var(--lx-muted,#94a3b8)}" +
    ".sxp-switch{position:relative;width:44px;height:26px;flex:none;border:0;border-radius:13px;background:var(--lx-line-strong,#475569)}" +
    ".sxp-switch[aria-checked=true]{background:var(--lx-teal,#14b8a6)}" +
    ".sxp-switch:after{content:'';position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#fff;transition:transform .18s}" +
    ".sxp-switch[aria-checked=true]:after{transform:translateX(18px)}" +
    ".sxp-list{overflow:auto;padding:6px 10px 14px}" +
    ".sxp-row{display:flex;align-items:center;gap:12px;padding:12px 8px;border-bottom:1px solid var(--lx-line,rgba(148,163,184,.14))}" +
    ".sxp-icon{flex:none;width:38px;height:38px;display:grid;place-items:center;border-radius:12px;background:var(--lx-teal-tint,rgba(20,184,166,.14));color:var(--lx-teal,#2dd4bf)}" +
    ".sxp-main{flex:1;min-width:0}.sxp-name{display:flex;align-items:center;gap:6px;min-width:0;font-weight:600}" +
    ".sxp-name>span:first-child{min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.sxp-name .sxp-tag{flex:none;margin-left:0}.sxp-meta{display:block}" +
    ".sxp-meta{color:var(--lx-muted,#94a3b8);font-size:12px}" +
    ".sxp-tag{display:inline-block;margin-left:6px;padding:1px 7px;border-radius:9px;font-size:11px;font-weight:600;background:rgba(20,184,166,.16);color:var(--lx-teal,#2dd4bf)}" +
    ".sxp-tag.sxp-new{background:rgba(239,68,68,.16);color:#f87171}" +
    ".sxp-go{flex:none;border:0;border-radius:10px;padding:9px 12px;background:var(--lx-teal,#14b8a6);color:var(--lx-on-teal,#04221d);font:600 13px/1 inherit}" +
    ".sxp-go[disabled]{opacity:.45}" +
    ".sxp-stop{background:#ef4444;color:#fff}.sxp-recorder small,.sxp-rec small{display:block}" +
    ".sxp-empty{padding:22px 14px;text-align:center;color:var(--lx-muted,#94a3b8)}" +
    ".sxp-empty b{display:block;color:var(--lx-ink,#e6edf6);font-size:15px;margin-bottom:6px}" +
    ".sxp-action{margin-top:14px;border:0;border-radius:12px;padding:11px 16px;background:var(--lx-teal,#14b8a6);color:var(--lx-on-teal,#04221d);font:600 14px/1 inherit}" +
    ".sxp-toast{position:fixed;left:16px;right:16px;bottom:calc(150px + env(safe-area-inset-bottom,0px));z-index:2147483003;padding:12px 14px;border-radius:14px;" +
    "background:var(--lx-surface,#0f1b2d);color:var(--lx-ink,#e6edf6);border:1px solid var(--lx-line-strong,rgba(148,163,184,.35));box-shadow:0 10px 30px rgba(0,0,0,.35);font:14px/1.4 var(--lx-font-ui,system-ui,sans-serif)}" +
    "@keyframes sxpup{from{transform:translateY(24px);opacity:0}to{transform:none;opacity:1}}@keyframes sxpfade{from{opacity:0}to{opacity:1}}";
  var style = document.createElement("style");
  style.textContent = css;
  document.head.appendChild(style);

  var WAVE =
    '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">' +
    '<path d="M2 10v4M6 6v12M10 3v18M14 8v8M18 5v14M22 10v4"/></svg>';

  function escape(text) {
    return String(text == null ? "" : text).replace(/[&<>"']/g, function (c) {
      return {
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;",
      }[c];
    });
  }
  function clock(ms) {
    if (!ms) return "";
    var s = Math.round(ms / 1000);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var r = String(s % 60).padStart(2, "0");
    return h ? h + ":" + String(m).padStart(2, "0") + ":" + r : m + ":" + r;
  }
  function when(ms) {
    var d = new Date(ms);
    return (
      d.toLocaleDateString("en-IN", { day: "numeric", month: "short" }) +
      " · " +
      d.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" })
    );
  }
  function megabytes(bytes) {
    return bytes
      ? (bytes / 1048576).toFixed(bytes > 10485760 ? 0 : 1) + " MB"
      : "";
  }

  var toastTimer;
  function toast(text) {
    var old = document.querySelector(".sxp-toast");
    if (old) old.remove();
    var node = document.createElement("div");
    node.className = "sxp-toast";
    node.setAttribute("role", "status");
    node.textContent = text;
    document.body.appendChild(node);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      node.remove();
    }, 5200);
  }

  var items = [];
  var state = null;
  // This phone's call recorder (RecorderProfile) and the in-app recorder (AppRecorder).
  var recorder = null;
  var rec = null;

  function refresh() {
    var profile = call("recorderStatus")
      .then(function (result) {
        recorder = result;
        rec = result.appRecorder || rec;
      })
      .catch(function () {});
    var calls = call("status")
      .then(function (status) {
        state = status;
        if (!status.audio && !status.allFiles && !status.folderPicked) {
          items = [];
          return;
        }
        return call("list", { limit: 200 }).then(function (result) {
          items = result.items || [];
        });
      })
      .catch(function () {});
    return Promise.all([profile, calls]);
  }

  var MAKERS = {
    xiaomi: "Xiaomi's Phone app",
    samsung: "Samsung's Phone app",
    oplus: "the Phone app",
    vivo: "Vivo's Phone app",
    huawei: "the Phone app",
  };

  /** One line on what this phone's recorder does, with the one fix it needs. */
  function recorderCard() {
    if (!recorder) return "";
    var app = escape(recorder.dialerLabel || "your Phone app");
    var text;
    var act = "";
    if (recorder.kind === "private") {
      var maker = (recorder.otherDialers || []).filter(function (d) {
        return d.savesToFolder;
      })[0];
      text =
        "<b>" +
        app +
        " keeps call recordings to itself</b><small>No other app can read them. " +
        (maker
          ? "Make " +
            escape(maker.label) +
            " your phone app and turn on its call recording, or record conversations here."
          : "Record conversations here, or use a phone whose own Phone app records calls.") +
        "</small>";
      if (maker)
        act =
          '<button type="button" class="sxp-go" data-rec="defaultDialer">Switch</button>';
    } else if (recorder.recording === "on") {
      var found = (recorder.folders || []).filter(function (f) {
        return f.state === "found";
      })[0];
      text =
        "<b>" +
        app +
        " records your calls</b><small>" +
        (found
          ? "Saved in " + escape(found.path) + " · " + found.recordings + " found"
          : "Sales X-Ray picks them up after each call") +
        "</small>";
    } else if (!recorder.canRead) {
      text =
        "<b>Let Sales X-Ray check your call recordings</b><small>It looks only in " +
        escape((MAKERS[recorder.maker] || app) + "'s") +
        " recordings folder.</small>";
      act = '<button type="button" class="sxp-go" data-act="allow">Allow</button>';
    } else {
      var where = (recorder.folders || [])[0];
      text =
        "<b>Turn on call recording in " +
        app +
        "</b><small>" +
        (recorder.recording === "off"
          ? "It's off. "
          : "No recordings yet. ") +
        "Phone settings → Call recording → Record calls automatically" +
        (where ? ". They save to " + escape(where.path) : "") +
        ".</small>";
      act =
        '<button type="button" class="sxp-go" data-rec="settings">Open</button>';
    }
    return '<div class="sxp-watch sxp-recorder"><span>' + text + "</span>" + act + "</div>";
  }

  var CAPTURED = {
    dialer: "Phone app",
    accessibility: "Call · Sales X-Ray",
    in_app: "Recorded here",
  };

  var callSetup = false;
  /**
   * The optional call recorder (CEO 19 Oct): off by default, an Accessibility
   * service, sideload builds only. The disclosure says exactly what it does.
   */
  function callCard() {
    var cr = recorder && recorder.callRecorder;
    if (!cr || !cr.available) return "";
    if (cr.wanted && cr.enabled) {
      return (
        '<div class="sxp-watch"><span><b>Records your phone calls · On</b><small>Your side is clear; the other side clearly only on speaker. Tell the other person the call is recorded.</small></span>' +
        '<button type="button" class="sxp-go" data-call="off">Turn off</button></div>'
      );
    }
    if (cr.wanted) {
      return (
        '<div class="sxp-watch sxp-recorder"><span><b>Almost on: allow the call recorder</b><small>In Accessibility, open Sales X-Ray call recorder and turn it on. If Android says “Restricted setting”, open App info, tap ⋮, Allow restricted settings, then try again.</small></span>' +
        '<span><button type="button" class="sxp-go" data-call="accessibility">Open</button> <button type="button" class="sxp-go" data-call="appInfo">App info</button></span></div>'
      );
    }
    if (callSetup) {
      return (
        '<div class="sxp-watch sxp-recorder"><span><b>Record phone calls in Sales X-Ray</b><small>' +
        "Android lets an app hear a call only through Accessibility, so Sales X-Ray uses it for this and nothing else. " +
        "It records only while a call is on: phone calls, and internet calls such as WhatsApp. It does not read your screen, your messages or any other app. " +
        "Recordings stay on this phone until you tap Analyse. Android will warn that Accessibility can see and control your phone: Sales X-Ray uses none of that. " +
        "Mostly your side is heard; put the call on speaker for both sides." +
        '</small></span><span><button type="button" class="sxp-go" data-call="on">Turn on</button> <button type="button" class="sxp-go" data-call="cancel">Not now</button></span></div>'
      );
    }
    return (
      '<div class="sxp-watch"><span><b>Record phone calls in Sales X-Ray</b><small>Off. For phones whose own Phone app can’t save recordings.</small></span>' +
      '<button type="button" class="sxp-go" data-call="setup">Set up</button></div>'
    );
  }

  /** Record a conversation in Sales X-Ray itself: a meeting, a walk-in, a talk on speaker. */
  function recordCard() {
    // The first Play version has no in-app recorder (owner, 24 Oct 2026); older apps send no "available".
    if (rec && rec.available === false) return "";
    if (rec && rec.recording) {
      return (
        '<div class="sxp-watch sxp-rec"><span><b>Recording · <span class="sxp-elapsed">' +
        clock(Date.now() - (rec.startedMs || Date.now())) +
        "</span></b><small>Keep Sales X-Ray near the people talking.</small></span>" +
        '<button type="button" class="sxp-go sxp-stop" data-rec="stop">Stop</button></div>'
      );
    }
    return (
      '<div class="sxp-watch"><span><b>Record a conversation</b><small>A meeting or a talk in person. Phone calls can’t be recorded by apps.</small></span>' +
      '<button type="button" class="sxp-go" data-rec="start">Record</button></div>'
    );
  }

  // Back from Android's settings (recording, Accessibility, Phone app): show what changed.
  document.addEventListener("visibilitychange", function () {
    var sheet = document.querySelector(".sxp-sheet");
    if (document.visibilityState !== "visible" || !sheet) return;
    refresh().then(function () {
      if (document.body.contains(sheet)) drawSheet(sheet);
    });
  });

  var ticker = null;
  function tick(sheet) {
    clearInterval(ticker);
    if (!rec || !rec.recording) return;
    var polls = 0;
    ticker = setInterval(function () {
      if (!document.body.contains(sheet)) return clearInterval(ticker);
      var node = sheet.querySelector(".sxp-elapsed");
      if (node) node.textContent = clock(Date.now() - (rec.startedMs || Date.now()));
      if (++polls % 3) return;
      call("recordState").then(function (next) {
        if (next.recording) return;
        rec = next;
        clearInterval(ticker);
        refresh().then(function () {
          drawSheet(sheet);
        });
      });
    }, 1000);
  }

  function closeSheet() {
    var sheet = document.querySelector(".sxp-sheet");
    var scrim = document.querySelector(".sxp-scrim");
    if (sheet) sheet.remove();
    if (scrim) scrim.remove();
  }

  function openSheet() {
    closeSheet();
    var scrim = document.createElement("div");
    scrim.className = "sxp-scrim";
    scrim.addEventListener("click", closeSheet);
    var sheet = document.createElement("section");
    sheet.className = "sxp-sheet";
    sheet.setAttribute("role", "dialog");
    sheet.setAttribute("aria-label", "Calls on this phone");
    document.body.appendChild(scrim);
    document.body.appendChild(sheet);
    drawSheet(sheet);
    refresh().then(function () {
      if (document.body.contains(sheet)) drawSheet(sheet);
      var newest = items.reduce(
        function (max, item) {
          return Math.max(max, item.addedMs);
        },
        load(SEEN_KEY, 0),
      );
      save(SEEN_KEY, newest);
    });
  }

  function drawSheet(sheet) {
    var sent = load(SENT_KEY, {});
    var seen = load(SEEN_KEY, 0);
    var watching = state && state.watching;
    var head =
      '<header class="sxp-head"><div class="sxp-grab"></div><div class="sxp-title"><h2>Calls on this phone</h2>' +
      '<button type="button" class="sxp-close" aria-label="Close">×</button></div>' +
      '<p class="sxp-sub">Your call recordings and conversations recorded here. Nothing leaves this phone until you tap Analyse.</p>' +
      recorderCard() +
      callCard() +
      recordCard() +
      (state && (state.audio || state.allFiles || state.folderPicked)
        ? '<div class="sxp-watch"><span><b>Tell me about new recordings</b><small>Checks every 15 minutes, even when Sales X-Ray is closed</small></span>' +
          '<button type="button" class="sxp-switch" role="switch" aria-label="Tell me about new recordings" aria-checked="' +
          Boolean(watching) +
          '"></button></div>'
        : "") +
      "</header>";
    var body;
    if (!state) {
      body = '<div class="sxp-empty">Looking for call recordings…</div>';
    } else if (!state.audio && !state.allFiles && !state.folderPicked) {
      body =
        '<div class="sxp-empty"><b>Let Sales X-Ray see your call recordings</b>' +
        "Allow access to audio files so Sales X-Ray can list the calls your phone recorded. Only the call you pick is uploaded." +
        '<br><button type="button" class="sxp-action" data-act="allow">Allow access</button></div>';
    } else if (!items.length) {
      body =
        '<div class="sxp-empty"><b>No recordings yet</b>' +
        (recorder && recorder.kind === "private"
          ? "Record a conversation above, or switch to a Phone app that saves call recordings."
          : "Turn on automatic call recording in your Phone app (see above). If it is already on, your phone may hide the folder.") +
        (state.allFilesSupported && !state.allFiles
          ? '<br><button type="button" class="sxp-action" data-act="allfiles">Let Sales X-Ray look in the call folder</button>'
          : !state.allFilesSupported && state.recordingsHidden && state.folderPickSupported
            ? '<br><button type="button" class="sxp-action" data-act="pickfolder">Choose your recordings folder</button>'
            : "") +
        "</div>";
    } else {
      body =
        '<div class="sxp-list">' +
        items
          .map(function (item, index) {
            var tag = sent[item.source]
              ? '<span class="sxp-tag">Sent</span>'
              : item.addedMs > seen
                ? '<span class="sxp-tag sxp-new">New</span>'
                : "";
            var meta = [
              CAPTURED[item.captured] || "",
              when(item.addedMs),
              clock(item.durationMs),
              megabytes(item.size),
            ]
              .filter(Boolean)
              .join(" · ");
            var note = item.supported
              ? ""
              : " · <span>." + escape(item.ext) + " not supported yet</span>";
            return (
              '<div class="sxp-row"><span class="sxp-icon">' +
              WAVE +
              '</span><span class="sxp-main"><span class="sxp-name"><span>' +
              escape(item.title) +
              "</span>" +
              tag +
              '</span><span class="sxp-meta">' +
              escape(meta) +
              note +
              "</span></span>" +
              '<button type="button" class="sxp-go" data-index="' +
              index +
              '"' +
              (item.supported ? "" : " disabled") +
              ">Analyse</button></div>"
            );
          })
          .join("") +
        "</div>";
    }
    sheet.innerHTML = head + body;
    sheet.querySelector(".sxp-close").addEventListener("click", closeSheet);
    var toggle = sheet.querySelector(".sxp-switch");
    if (toggle)
      toggle.addEventListener("click", function () {
        call("setWatch", { on: !(state && state.watching) }).then(
          function (status) {
            state = status;
            drawSheet(sheet);
            toast(
              status.watching
                ? status.notifications
                  ? "Sales X-Ray will tell you about new call recordings."
                  : "Turn on notifications for Sales X-Ray to get alerts."
                : "New-recording alerts are off.",
            );
          },
        );
      });
    Array.prototype.forEach.call(sheet.querySelectorAll("[data-call]"), function (button) {
      button.addEventListener("click", function () {
        var what = button.getAttribute("data-call");
        var redraw = function () {
          refresh().then(function () {
            drawSheet(sheet);
          });
        };
        if (what === "setup") {
          callSetup = true;
          return drawSheet(sheet);
        }
        if (what === "cancel") {
          callSetup = false;
          return drawSheet(sheet);
        }
        if (what === "off") return call("callRecorderSet", { on: false }).then(redraw);
        if (what === "on") {
          callSetup = false;
          var cr = (recorder && recorder.callRecorder) || {};
          var consent = cr.consentShown
            ? Promise.resolve()
            : call("callRecorderConsent").then(function () {
                window.alert("Tell the other person the call is recorded.");
              });
          return consent
            .then(function () {
              return call("callRecorderSet", { on: true });
            })
            .then(function (next) {
              if (!next.enabled) {
                call("callRecorderOpen", { what: "accessibility" });
                toast("Open Sales X-Ray call recorder and turn it on, then come back.");
              }
              redraw();
            });
        }
        call("callRecorderOpen", { what: what });
      });
    });
    Array.prototype.forEach.call(sheet.querySelectorAll("[data-rec]"), function (button) {
      button.addEventListener("click", function () {
        var what = button.getAttribute("data-rec");
        if (what === "start") {
          button.disabled = true;
          call("recordStart", {}).then(function (result) {
            rec = result;
            if (result.outcome === "in_call")
              toast("Android doesn't let apps record during a phone call. Record after the call, or in person.");
            else if (result.outcome === "no_mic")
              toast("Allow the microphone for Sales X-Ray to record conversations.");
            else rec = { recording: true, startedMs: Date.now() };
            drawSheet(sheet);
            tick(sheet);
          });
        } else if (what === "stop") {
          button.disabled = true;
          call("recordStop").then(function () {
            toast("Saved. It's at the top of the list: tap Analyse to send it.");
            setTimeout(function () {
              call("recordState").then(function (next) {
                rec = next;
                refresh().then(function () {
                  drawSheet(sheet);
                });
              });
            }, 700);
          });
        } else {
          call("recorderOpen", { what: what }).then(function (result) {
            if (!result.opened) toast("Open your Phone app's settings and look for Call recording.");
            else if (what === "defaultDialer")
              toast("Choose your phone maker's Phone app, then turn on its call recording.");
            else toast("Turn on Record calls automatically, then come back.");
          });
        }
      });
    });
    Array.prototype.forEach.call(sheet.querySelectorAll('[data-act="allow"]'), function (allow) {
      allow.addEventListener("click", function () {
        call("requestAccess").then(function () {
          refresh().then(function () {
            drawSheet(sheet);
          });
        });
      });
    });
    var pickFolder = sheet.querySelector('[data-act="pickfolder"]');
    if (pickFolder)
      pickFolder.addEventListener("click", function () {
        toast("Your phone's call recordings folder opens. Tap “Use this folder”, then “Allow”.");
        call("pickRecordingsFolder")
          .then(function () {
            return refresh();
          })
          .then(function () {
            drawSheet(sheet);
          })
          .catch(function () {});
      });
    var allFiles = sheet.querySelector('[data-act="allfiles"]');
    if (allFiles)
      allFiles.addEventListener("click", function () {
        call("openAllFiles");
        toast(
          "Turn on “Allow access to manage all files” for Sales X-Ray, then come back.",
        );
      });
    Array.prototype.forEach.call(
      sheet.querySelectorAll(".sxp-go[data-index]"),
      function (button) {
        button.addEventListener("click", function () {
          analyse(items[Number(button.getAttribute("data-index"))]);
        });
      },
    );
    tick(sheet);
  }

  function waitFor(selector, ms) {
    return new Promise(function (resolve, reject) {
      var started = Date.now();
      (function look() {
        var node = document.querySelector(selector);
        if (node) return resolve(node);
        if (Date.now() - started > ms)
          return reject(new Error("upload_area_missing"));
        setTimeout(look, 250);
      })();
    });
  }

  function readAll(item, progress) {
    var chunks = [];
    var offset = 0;
    function next() {
      return call("read", { source: item.source, offset: offset }).then(
        function (result) {
          var binary = atob(result.data);
          var bytes = new Uint8Array(binary.length);
          for (var i = 0; i < binary.length; i++)
            bytes[i] = binary.charCodeAt(i);
          chunks.push(bytes);
          offset += bytes.length;
          progress(
            item.size
              ? Math.min(99, Math.round((offset / item.size) * 100))
              : 0,
          );
          return result.eof || bytes.length === 0 ? chunks : next();
        },
      );
    }
    return next();
  }

  function analyse(item) {
    if (!item || !item.supported) return;
    if (location.pathname !== "/analysis/new") {
      save(PICK_KEY, item.source);
      location.assign("/analysis/new");
      return;
    }
    closeSheet();
    toast("Preparing " + item.title + "…");
    waitFor('[role="group"][aria-label="Add sales call audio files"]', 15000)
      .then(function (area) {
        return readAll(item, function (percent) {
          var node = document.querySelector(".sxp-toast");
          if (node)
            node.textContent = "Preparing " + item.title + "… " + percent + "%";
        }).then(function (chunks) {
          var file = new File(chunks, item.name, {
            type: item.mime,
            lastModified: item.addedMs,
          });
          // How it was captured (ADR 0056 F2): the web upload sends this note as
          // X-Capture-Source (app/capture-source.ts reads the same key).
          var captured = {
            dialer: "android_dialer_pickup",
            in_app: "android_in_app_recorder",
            accessibility: "android_call_recorder",
          }[item.captured];
          if (captured) {
            try {
              sessionStorage.setItem("sx.capture:" + file.name + ":" + file.size, captured);
            } catch (error) {}
          }
          // Right after a page load the upload area is drawn before React listens
          // to it, and an early drop is lost (Redmi, 19 Oct). Drop, then check the
          // file is listed; try again for a few seconds.
          return dropUntilAdded(file);
        }).then(function () {
          var sent = load(SENT_KEY, {});
          sent[item.source] = Date.now();
          save(SENT_KEY, sent);
          toast("Recording added. Check the details and start the analysis.");
        });
      })
      .catch(function () {
        toast(
          "That recording could not be added. Open New analysis and try again.",
        );
      });
  }

  function dropUntilAdded(file) {
    var tries = 0;
    function added() {
      return !!document.querySelector('[aria-label="Added audio files"] li');
    }
    return new Promise(function (resolve, reject) {
      (function drop() {
        var area = document.querySelector(
          '[role="group"][aria-label="Add sales call audio files"]',
        );
        if (added()) return resolve();
        if (++tries > 8) return reject(new Error("not_added"));
        if (area) {
          var transfer = new DataTransfer();
          transfer.items.add(file);
          area.dispatchEvent(
            new DragEvent("drop", {
              bubbles: true,
              cancelable: true,
              dataTransfer: transfer,
            }),
          );
        }
        setTimeout(function () {
          if (added()) resolve();
          else drop();
        }, 1500);
      })();
    });
  }

  function continuePick() {
    var source = load(PICK_KEY, null);
    var launch = call("consumeLaunch").then(function (result) {
      return result && result.source;
    });
    return launch.then(function (fromNotice) {
      var wanted = fromNotice || source;
      if (!wanted) return;
      save(PICK_KEY, null);
      return refresh().then(function () {
        var item = items.filter(function (candidate) {
          return candidate.source === wanted;
        })[0];
        if (!item) return openSheet();
        analyse(item);
      });
    });
  }

  // The status and navigation bars take the colours at the very top and bottom
  // of the page, whatever the site's own light or dark setting. A 1-pixel canvas
  // turns any CSS colour (oklch included) into #rrggbb; see sxPhone "bars".
  var probe = document.createElement("canvas").getContext("2d", {
    willReadFrequently: true,
  });
  function opaqueHex(color) {
    if (!probe || !color) return "";
    probe.clearRect(0, 0, 1, 1);
    probe.fillStyle = "rgba(0,0,0,0)";
    probe.fillStyle = color;
    probe.fillRect(0, 0, 1, 1);
    var pixel = probe.getImageData(0, 0, 1, 1).data;
    if (pixel[3] < 250) return "";
    return (
      "#" +
      [pixel[0], pixel[1], pixel[2]]
        .map(function (n) {
          return (n < 16 ? "0" : "") + n.toString(16);
        })
        .join("")
    );
  }
  function colourAt(y) {
    var node = document.elementFromPoint(Math.floor(window.innerWidth / 2), y);
    for (; node; node = node.parentElement) {
      var hex = opaqueHex(getComputedStyle(node).backgroundColor);
      if (hex) return hex;
    }
    return "";
  }
  var painted = "";
  function paintBars() {
    var status = colourAt(1);
    if (!status) return;
    var nav = colourAt(window.innerHeight - 2) || status;
    if (status + nav === painted) return;
    painted = status + nav;
    call("bars", { status: status, nav: nav }).catch(function () {
      painted = "";
    });
  }
  var paintTimer;
  function schedulePaint() {
    clearTimeout(paintTimer);
    paintTimer = setTimeout(paintBars, 120);
  }
  new MutationObserver(schedulePaint).observe(document.documentElement, {
    attributes: true,
  });
  if (document.body)
    new MutationObserver(schedulePaint).observe(document.body, {
      attributes: true,
    });
  ["pushState", "replaceState"].forEach(function (name) {
    var original = history[name];
    history[name] = function () {
      var result = original.apply(this, arguments);
      schedulePaint();
      dropAlertsWhenSignedOut();
      return result;
    };
  });
  window.addEventListener("popstate", schedulePaint);
  if (window.matchMedia)
    window
      .matchMedia("(prefers-color-scheme: dark)")
      .addEventListener("change", schedulePaint);
  setInterval(paintBars, 3000);

  // A signed-out phone drops its lead-alert address, so no alert reaches it.
  var signInPaths = /^\/(login|register|auth)(\/|$)/;
  function dropAlertsWhenSignedOut() {
    if (signInPaths.test(location.pathname)) call("pushReset").catch(function () {});
  }

  window.__sxPhone = {
    onNative: function (event) {
      // The page listens too: "resume" refreshes the Leads desk, "alert" as well.
      try {
        window.dispatchEvent(
          new CustomEvent("sx-phone", { detail: { event: event } }),
        );
      } catch (error) {}
      if (event === "launch") continuePick();
      else if (event !== "bars") refresh();
      // Back in the app, or light/dark mode changed: measure and paint again.
      painted = "";
      schedulePaint();
    },
    // "Calls on this phone" in the page's app menu.
    openCalls: openSheet,
  };

  schedulePaint();
  dropAlertsWhenSignedOut();
  refresh().then(continuePick);
})();
