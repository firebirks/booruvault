package io.github.firebirks.booruvault.net

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * A WebView that keeps what is typed in it to itself: the keyboard is asked not to learn from
 * it, and the system autofill service is kept out, so neither saves logins or searches.
 */
@SuppressLint("ViewConstructor")
class PrivateWebView(context: Context) : WebView(context) {
    init {
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
}

/**
 * Removes WebRTC before any page script runs: its peer connections would bypass the proxy, and
 * a STUN server would learn the phone's own address. Taken out of the page's window alone it
 * stays within reach: a frame's first, empty document gets no document-start script, and a page
 * that makes a frame, by any means, could take WebRTC from it at once. So every frame the page
 * can reach is cleared too, as soon as anything could make one or reach into one: after each
 * insertion of nodes, by script or by the parser, on reading a frame's window or document, on a
 * frame's load, in the page's own custom elements, and in a window it opens. Checked on a phone
 * against eleven ways a page reaches a new frame, the parser, document.write, innerHTML, shadow
 * roots, nested frames, execCommand and code that runs during an insertion among them.
 *
 * The functions replaced answer as the natives they wrap, in name, length, attributes and source
 * text, and cannot be used as constructors: anti-bot checks, which the built-in browser is there
 * to pass, look for natives a script has replaced. This is the most a page script can do; the
 * WebView has no setting that turns WebRTC off.
 */
const val DISABLE_WEBRTC_SCRIPT = """
(function () {
  var NAMES = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCSessionDescription', 'RTCIceCandidate'];
  var hooked = new WeakSet();
  var originals = new WeakMap();
  var top = window;

  // A wrapper answers as the function it wraps: name, length, source text, and, made as a method,
  // no prototype and no use as a constructor. Anti-bot checks look for natives that were replaced.
  function disguise(wrapper, original) {
    try { Object.defineProperty(wrapper, 'name', { value: original.name, configurable: true }); } catch (e) {}
    try { Object.defineProperty(wrapper, 'length', { value: original.length, configurable: true }); } catch (e) {}
    originals.set(wrapper, original);
    return wrapper;
  }
  function method(original, body) {
    return disguise({ m() { return body(this, arguments, original); } }.m, original);
  }
  function getter(original, body) {
    return disguise(Object.getOwnPropertyDescriptor({ get g() { return body(this, original); } }, 'g').get, original);
  }
  function setter(original, body) {
    return disguise(Object.getOwnPropertyDescriptor({ set s(v) { body(this, v, original); } }, 's').set, original);
  }

  // In place of the property [name] of [owner], with the same attributes as the one it replaces.
  function replace(owner, name, value) {
    var d = Object.getOwnPropertyDescriptor(owner, name);
    if (!d) return;
    try { Object.defineProperty(owner, name, { value: value, writable: d.writable, enumerable: d.enumerable, configurable: d.configurable }); } catch (e) {}
  }

  function removed(w) {
    var d = Object.getOwnPropertyDescriptor(w, 'RTCPeerConnection');
    return !!d && d.value === undefined && d.configurable === false;
  }
  function remove(w) {
    NAMES.forEach(function (name) {
      try { Object.defineProperty(w, name, { value: undefined, writable: false, configurable: false }); }
      catch (e) { try { w[name] = undefined; } catch (ignored) {} }
    });
    try { Object.defineProperty(w.navigator, 'mediaDevices', { value: undefined, configurable: false }); } catch (e) {}
  }
  function children(w) {
    var list = [];
    try { for (var i = 0; i < w.length; i++) list.push(w[i]); } catch (e) {}
    return list;
  }

  // Every frame the page can reach, its own first. A frame's first, empty document gets no
  // document-start script, and a page that makes one could take WebRTC from it; a frame of
  // another origin cannot be reached, and each of its documents gets the script.
  function guard(w) {
    try { void w.document; } catch (e) { return; }
    if (!removed(w)) remove(w);
    harden(w);
    children(w).forEach(guard);
  }
  function sweep() { guard(top); }

  function harden(w) {
    var nodeProto;
    try { nodeProto = w.Node && w.Node.prototype; } catch (e) { return; }
    if (!nodeProto || hooked.has(nodeProto)) return;
    hooked.add(nodeProto);

    function after(proto, name) {
      var original = proto && proto[name];
      if (typeof original !== 'function') return;
      replace(proto, name, method(original, function (self, args, f) { try { return f.apply(self, args); } finally { sweep(); } }));
    }
    function afterSet(proto, name) {
      var d = proto && Object.getOwnPropertyDescriptor(proto, name);
      if (!d || !d.set) return;
      var set = setter(d.set, function (self, v, f) { try { f.call(self, v); } finally { sweep(); } });
      try { Object.defineProperty(proto, name, { get: d.get, set: set, configurable: d.configurable, enumerable: d.enumerable }); } catch (e) {}
    }
    function guardedGet(proto, name, windowOf) {
      var d = proto && Object.getOwnPropertyDescriptor(proto, name);
      if (!d || !d.get) return;
      var get = getter(d.get, function (self, f) { var v = f.call(self); var fw = windowOf(v); if (fw) guard(fw); return v; });
      try { Object.defineProperty(proto, name, { get: get, set: d.set, configurable: d.configurable, enumerable: d.enumerable }); } catch (e) {}
    }

    // What makes frames, by script: each is looked over once it is done.
    ['appendChild', 'insertBefore', 'replaceChild'].forEach(function (n) { after(nodeProto, n); });
    ['append', 'prepend', 'before', 'after', 'replaceWith', 'replaceChildren', 'insertAdjacentElement', 'insertAdjacentHTML', 'setHTMLUnsafe']
      .forEach(function (n) { after(w.Element && w.Element.prototype, n); });
    ['append', 'prepend', 'replaceChildren', 'write', 'writeln', 'open', 'execCommand'].forEach(function (n) { after(w.Document && w.Document.prototype, n); });
    ['before', 'after', 'replaceWith'].forEach(function (n) { after(w.CharacterData && w.CharacterData.prototype, n); });
    ['insertNode', 'surroundContents'].forEach(function (n) { after(w.Range && w.Range.prototype, n); });
    ['innerHTML', 'outerHTML'].forEach(function (n) { afterSet(w.Element && w.Element.prototype, n); });
    afterSet(w.ShadowRoot && w.ShadowRoot.prototype, 'innerHTML');
    after(w.ShadowRoot && w.ShadowRoot.prototype, 'setHTMLUnsafe');
    // What reaches into a frame.
    ['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement', 'HTMLEmbedElement'].forEach(function (c) {
      var proto = w[c] && w[c].prototype;
      guardedGet(proto, 'contentWindow', function (v) { return v; });
      guardedGet(proto, 'contentDocument', function (v) { return v && v.defaultView; });
    });
    try {
      var open = w.open;
      if (typeof open === 'function') {
        replace(w, 'open', method(open, function (self, args, f) { var opened = f.apply(self, args); if (opened) guard(opened); return opened; }));
      }
    } catch (e) {}
    // The page's own custom elements run during an insertion, before it returns: they find
    // the frames it made guarded already.
    try {
      var registry = w.CustomElementRegistry && w.CustomElementRegistry.prototype;
      if (registry && typeof registry.define === 'function') {
        var define = method(registry.define, function (self, args, f) {
          var ctor = args[1];
          try {
            ['connectedCallback', 'disconnectedCallback', 'adoptedCallback', 'attributeChangedCallback', 'connectedMoveCallback'].forEach(function (cb) {
              var proto = ctor && ctor.prototype;
              var callback = proto && proto[cb];
              if (typeof callback === 'function') proto[cb] = function () { sweep(); return callback.apply(this, arguments); };
            });
          } catch (e) {}
          sweep();
          return f.apply(self, args);
        });
        replace(registry, 'define', define);
      }
    } catch (e) {}
    // The source text of a wrapper is its original's.
    try {
      var fnProto = w.Function.prototype;
      var toString = fnProto.toString;
      if (!originals.has(toString)) {
        replace(fnProto, 'toString', method(toString, function (self, args, f) { return f.call(originals.get(self) || self); }));
      }
    } catch (e) {}
    // Frames the parser makes are found before the next script runs: the parser checks for
    // microtasks before each script, and the observer is told then. A frame's load, which for
    // an empty one comes during its insertion, is seen before the page's own handlers on it:
    // on the document, as a frame's load does not reach the window.
    try { new w.MutationObserver(sweep).observe(w.document, { childList: true, subtree: true }); } catch (e) {}
    try { w.document.addEventListener('load', sweep, true); } catch (e) {}
  }

  guard(top);
})();
"""
