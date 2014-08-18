function getPos(el) {
  var r = { left: 0, top: 0 };
  while (el) {
    r.left += el.offsetLeft;
    r.top += el.offsetTop;
    el = el.offsetParent;
  }
  return r;
}

function popup(className) {
  var els = document.getElementsByClassName(className);
  for (var i = 0; i < els.length; i++) {
    els[i].style.display = (els[i].style.display == "none") ? "" : "none";
  }
}

var onHoverEvtPrev;
function onHoverEvt(el) {
  if (el) {
    var c = el.className.split(' ')[1];
    if (c != onHoverEvtPrev) {
      document.getElementById("hover-style").innerText =
        "." + c + " { background: #3a3a3a; }";
      onHoverEvtPrev = c;
    }
  }
}

var dragMeta = {
  ew: [{ prop: "width",  eventProp: "X", fn: dragByPct,
         parentProp: "offsetWidth", posProp: "left" }],
  ns: [{ prop: "height", eventProp: "Y", fn: dragByPct,
         parentProp: "offsetHeight", posProp: "top" }],
  addr: [{ prop: "left", eventProp: "X", fn: onDragNetAddrPx },
         { prop: "top", eventProp: "Y", fn: onDragNetAddrPx }]
}

function startDrag(dir, idMain, idRest, onDone) {
  var startXY = [window.event.clientX, window.event.clientY];
  var offsetXY = [window.event.offsetX, window.event.offsetY];
  window.onmousemove = function(ev) {
    for (var i = 0; i < dragMeta[dir].length; i++) {
      var p = document.getElementById(idMain).parentElement;
      var s = dragMeta[dir][i];
      s.fn(ev, p, s, offsetXY,
           ev["client" + s.eventProp] - getPos(p)[s.posProp || s.prop], idMain, idRest);
    }
    return false;
  }
  window.onmouseup = function(ev) {
    window.onmouseup = window.onmousemove = null;
    if (onDone && startXY[0] != ev.clientX && startXY[1] != ev.clientY) {
      onDone(dir, idMain, idRest);
    }
  }
  return false;
}

function dragByPct(ev, p, s, offsetXY, v, idMain, idRest) {
  var pct = Math.max(10, Math.min(95, Math.round(100 * ((1.0 * v) / p[s.parentProp]))));
  var m = document.getElementById(idMain);
  if (m) {
    m.style[s.prop] = pct + "%";
  }
  var r = document.getElementById(idRest);
  if (r) {
    r.style[s.prop] = (100 - pct) + "%";
  }
}

function onDragNetAddrPx(ev, p, s, offsetXY, v, idMain) {
  if (s.prop == "left") {
    onDragNetAddr(null, idMain, null, v - offsetXY[0], null);
  } else {
    onDragNetAddr(null, idMain, null, null, v - offsetXY[1]);
  }
}

function onDragNetAddr(dir, idMain, idRest, x, y) {
  var addr = idMain.split("-").splice(1).join("-"); // Convert "addr-foo" to "foo".
  var m = document.getElementById(idMain);
  cbfg.world.t1.addr_override_xy(addr,
                                 x || parseInt(m.style.left),
                                 y || parseInt(m.style.top));
}

document.getElementById("prog-eval-selection").onclick = function() {
  var t = document.getElementById("prog-in");
  var s = [t.selectionStart, t.selectionEnd];
  console.log(eval("with (" + world_t + ") {" + t.value.slice(s[0], s[1]) + "}"));
  t.selectionStart = s[0];
  t.selectionEnd = s[1];
}

var showSourceMap = {};
var showSourceCurr = null;

function showSource(name, line, after) {
  if (name == null || line == null) {
    return;
  }
  if (showSourceMap[name] == null) {
    showSourceMap[name] = "...loading...";
    var xhr = new XMLHttpRequest();
    xhr.onload = function() {
      if (xhr.status == 200) {
        var t = xhr.responseText.
                    replace(/&/g, "&amp;").
                    replace(/</g, "&lt;").
                    replace(/>/g, "&gt;");
        t = "<pre>" + t.replace(/\n/g, " </pre><pre>") + " </pre>";
        showSourceMap[name] = t
        showSource(name, line);
      }
    }
    var parts = name.split(".");
    var dir = parts.slice(0, parts.length - 1).join("/");
    xhr.open("get", "src/" + dir + "/" + parts[parts.length - 1] + ".cljs");
    xhr.send();
  } else {
    var s = document.getElementById("source");
    if (showSourceCurr != name) {
      s.innerHTML = showSourceMap[name];
    }
    showSourceCurr = name;
    requestAnimationFrame(function() {
      var els = s.getElementsByClassName("current-line");
      for (var i = 0; i < els.length; i++) {
        els[i].className = "";
      }
      var c = s.childNodes[line - 1];
      if (c) {
        c.scrollIntoViewIfNeeded();
        c.className = "current-line " + (after ? "event-after" : "");
      }
    });
  }
}
