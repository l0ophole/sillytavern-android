# Writing extensions

An extension is a folder (shipped as a `.zip`) with a `manifest.json` and one JavaScript file. It runs in a QuickJS sandbox: plain ECMAScript with `JSON`, `Math`, `Date`, `RegExp` and the global `st` and `console` described here. There is **no DOM, no jQuery, no `window`, no `setTimeout`, no `Promise`-based API and no `require`/`import`**. Unmodified SillyTavern browser extensions do not run.

```
my-ext.zip
  manifest.json
  index.js
```

```json
{
  "id": "word-count",
  "name": "Word count",
  "version": "1.0.0",
  "description": "Adds /wc and a {{wc}} macro.",
  "author": "you",
  "script": "index.js",
  "permissions": ["commands", "macros"]
}
```

`id` is 1-64 characters of `a-z 0-9 . _ -`. `script` defaults to `index.js`. Installing a zip with an existing id replaces it, keeps its stored data, and turns it off until you confirm its permissions again. Limits: 200 files, 2 MB per file, 8 MB total.

## Permissions

The user sees the list when turning an extension on. A call without its permission throws an `Error` in the script.

| Permission | Allows |
|---|---|
| `commands` | `st.registerCommand`, `st.runCommand` |
| `macros` | `st.registerMacro` |
| `prompt` | `st.setPrompt` |
| `chat` | `st.sendMessage`, `st.addMessage` |
| `generate` | `st.generateQuiet` |
| `network` | `st.fetch` |

`st.on`, `st.echo`, `st.context`, `st.messages`, `st.storage` and `console` need no permission.

## API (all calls are synchronous)

- `st.on(event, fn)`, `st.off(event, fn)`: events are `chat_changed` (`{}`), `message_sent` (`{name, text}`), `message_received` (`{name, text, index}`), `generation_started`, `generation_ended` (`{success}`). Handlers cannot change messages. A handler that throws is logged and the others still run.
- `st.registerCommand(name, fn, help)`: `/name` runs `fn({args, text, pipe})` and the returned string becomes the pipe. `name` is 1-32 characters of `a-z 0-9 _ -`; built-in commands cannot be replaced.
- `st.registerMacro(name, fn)`: `{{name::a::b}}` calls `fn(["a","b"])`. Built-in macros win. Macros run while the prompt is built, so inside a macro `runCommand`, `generateQuiet`, `sendMessage`, `addMessage` and `fetch` throw, and a macro has 1 second.
- `st.runCommand(text)`: runs a slash command line and returns its pipe.
- `st.setPrompt(text, {position, depth, role})`: one prompt per extension, added to every generation until replaced; empty text (or `st.clearPrompt()`) removes it. `position` 0 in prompt, 1 in chat at `depth`, 2 before the main prompt; `role` 0 system, 1 user, 2 assistant. Up to 8000 characters.
- `st.generateQuiet(prompt, useHistory)`: asks the main model without touching the chat.
- `st.sendMessage(text)`, `st.addMessage(name, text)`, `st.echo(text)`.
- `st.context()` → `{character, user, chatName, messageCount, groupChat}`; `st.messages(n)` → last `n` (max 200) of `{name, text, isUser, isSystem}`.
- `st.fetch(url, {method, headers, body})` → `{status, body}`; http(s) only, 15 s, response cut at 1 MB.
- `st.storage.get/set/remove/keys`: string values saved in `storage.json` (512 KB per extension).
- `console.log/warn/error` appear in the extension's log (Settings → Extensions → Details).

## Limits and failure

Every call into a script has 5 seconds. QuickJS cannot be interrupted, so a script that runs longer is **stopped for the session** (shown as "Stopped after an error"); turn it off and on to restart it. Memory is not capped. A script that fails to load is marked failed and the others keep running. A handler that sends a message from `message_sent` will trigger itself: guard against loops.

## Example

```js
st.registerCommand('wc', function (c) {
  var n = c.text.trim() ? c.text.trim().split(/\s+/).length : 0;
  return String(n);
}, 'Counts the words of its argument');

st.registerMacro('wc', function (args) {
  return String((args[0] || '').split(/\s+/).filter(Boolean).length);
});

st.on('message_received', function (m) {
  st.storage.set('lastReplyWords', String(m.text.split(/\s+/).length));
});
```
