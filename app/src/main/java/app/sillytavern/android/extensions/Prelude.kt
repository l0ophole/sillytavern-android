package app.sillytavern.android.extensions

/**
 * JavaScript that builds the `st` API over the single native bridge `__host.call(name, json) -> json`.
 * Results are `{value}` or `{error}`; handlers registered from scripts are reached through `__d.dispatch(kind, json)`.
 */
internal object Prelude {
    const val SOURCE = """
var __d, st, console;
(function () {
  var handlers = {}, commands = {}, macros = {}, storageCache = null;
  function call(name, args) {
    var r = JSON.parse(__host.call(name, JSON.stringify(args === undefined ? null : args)));
    if (r && r.error) throw new Error(r.error);
    return r ? r.value : undefined;
  }
  function fmt(a) {
    return Array.prototype.map.call(a, function (x) {
      if (typeof x === 'string') return x;
      try { return JSON.stringify(x); } catch (e) { return String(x); }
    }).join(' ');
  }
  console = {
    log: function () { call('log', { level: 'info', text: fmt(arguments) }); },
    info: function () { call('log', { level: 'info', text: fmt(arguments) }); },
    warn: function () { call('log', { level: 'warn', text: fmt(arguments) }); },
    error: function () { call('log', { level: 'error', text: fmt(arguments) }); }
  };
  st = {
    /** Subscribe to chat_changed, message_sent, message_received, generation_started, generation_ended. */
    on: function (event, fn) {
      if (typeof fn !== 'function') throw new Error('st.on needs a function');
      (handlers[event] = handlers[event] || []).push(fn);
    },
    off: function (event, fn) {
      handlers[event] = (handlers[event] || []).filter(function (f) { return f !== fn; });
    },
    /** fn({ args: {named}, text, pipe }) -> string. Needs the "commands" permission. */
    registerCommand: function (name, fn, help) {
      if (typeof fn !== 'function') throw new Error('registerCommand needs a function');
      call('registerCommand', { name: name, help: help || '' });
      commands[String(name).toLowerCase()] = fn;
    },
    /** fn(args: string[]) -> string. Needs the "macros" permission. */
    registerMacro: function (name, fn) {
      if (typeof fn !== 'function') throw new Error('registerMacro needs a function');
      call('registerMacro', { name: name });
      macros[String(name).toLowerCase()] = fn;
    },
    runCommand: function (text) { return call('runCommand', { text: String(text) }); },
    /** opts: { position: 0 in prompt | 1 in chat | 2 before prompt, depth, role: 0 system | 1 user | 2 assistant } */
    setPrompt: function (text, opts) { call('setPrompt', { text: String(text), opts: opts || {} }); },
    clearPrompt: function () { call('setPrompt', { text: '', opts: {} }); },
    generateQuiet: function (prompt, useHistory) { return call('generateQuiet', { prompt: String(prompt), useHistory: !!useHistory }); },
    sendMessage: function (text) { call('sendMessage', { text: String(text) }); },
    addMessage: function (name, text) { call('addMessage', { name: String(name), text: String(text) }); },
    echo: function (text) { call('echo', { text: String(text) }); },
    /** { character, user, chatName, messageCount, groupChat } */
    context: function () { return call('context'); },
    /** Chat lines { name, text, isUser, isSystem } for the last n messages (default 20, at most 200). */
    messages: function (n) { return call('messages', { n: n === undefined ? 20 : n }); },
    /** { status, body } ; opts: { method, headers, body }. Needs the "network" permission. */
    fetch: function (url, opts) { return call('fetch', { url: String(url), opts: opts || {} }); },
    storage: {
      get: function (key) { return call('storageGet', { key: String(key) }); },
      set: function (key, value) { call('storageSet', { key: String(key), value: String(value) }); },
      remove: function (key) { call('storageRemove', { key: String(key) }); },
      keys: function () { return call('storageKeys'); }
    },
    version: 1
  };
  __d = {
    dispatch: function (kind, json) {
      try {
        var a = JSON.parse(json), v;
        if (kind === 'event') {
          (handlers[a.name] || []).slice().forEach(function (f) { try { f(a.data); } catch (e) { console.error(String(e && e.message || e)); } });
          v = null;
        } else if (kind === 'command') {
          var c = commands[String(a.name).toLowerCase()];
          if (!c) throw new Error('No such command: ' + a.name);
          v = c({ args: a.args || {}, text: a.text || '', pipe: a.pipe || '' });
          v = v === undefined || v === null ? '' : String(v);
        } else if (kind === 'macro') {
          var m = macros[String(a.name).toLowerCase()];
          if (!m) throw new Error('No such macro: ' + a.name);
          v = m(a.args || []);
          v = v === undefined || v === null ? '' : String(v);
        } else throw new Error('Unknown dispatch kind ' + kind);
        return JSON.stringify({ value: v });
      } catch (e) {
        return JSON.stringify({ error: String(e && e.message || e) });
      }
    }
  };
})();
"""
}
