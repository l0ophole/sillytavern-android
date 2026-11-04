var _rc = st.registerCommand;
st.registerCommand = function (n, f, h) { _rc(n, function (c) { var r; try { r = f(c); } catch (e) { r = 'THREW ' + e.message; } console.log('RESULT ' + n + ' -> ' + r); return r; }, h); };
var BASE = 'http://192.168.1.133:8765';
function log(k, v) { var cur = st.storage.get('log') || ''; st.storage.set('log', cur + k + '=' + v + ';'); console.log(k + '=' + v); }
['chat_changed','message_sent','message_received','generation_started','generation_ended'].forEach(function (ev) {
  st.on(ev, function (p) { log('ev:' + ev, JSON.stringify(p || {}).slice(0, 80)); });
});
st.registerCommand('t-fetch', function () {
  var r = st.fetch(BASE + '/v1/models', {method: 'GET'});
  return 'FETCH ' + r.status + ' ' + r.body.slice(0, 40);
}, 'fetch test');
st.registerCommand('t-fetchbad', function () {
  try { st.fetch('ftp://x/y'); return 'NOTHROW'; } catch (e) { return 'FETCHBAD threw: ' + e.message; }
}, 'fetch scheme test');
st.registerCommand('t-quiet', function () {
  return 'QUIET ' + st.generateQuiet('Say hi', false);
}, 'generateQuiet test');
st.registerCommand('t-prompt', function (c) {
  st.setPrompt(c.text || 'PROMPTMARK-7731', {position: 2, role: 0}); return 'PROMPT set';
}, 'setPrompt test');
st.registerCommand('t-clear', function () { st.clearPrompt(); return 'PROMPT cleared'; }, 'clearPrompt');
st.registerCommand('t-send', function (c) { st.sendMessage(c.text || 'sent-by-ext'); return 'SEND ok'; }, 'sendMessage test');
st.registerCommand('t-add', function () { st.addMessage('Narrator', 'added-by-ext'); return 'ADD ok'; }, 'addMessage test');
st.registerCommand('t-ctx', function () { var c = st.context(); return 'CTX ' + c.character + '/' + c.user + '/' + c.messageCount + ' msgs=' + st.messages(2).length; }, 'context');
st.registerCommand('t-log', function () { return 'LOG ' + (st.storage.get('log') || '(empty)'); }, 'show event log');
st.registerCommand('t-logclear', function () { st.storage.remove('log'); return 'LOG cleared'; }, 'clear log');
st.registerMacro('t-quiet', function () { try { st.generateQuiet('x'); return 'NOTHROW'; } catch (e) { return 'MACROBLOCKED'; } });
