# Groups + Tags (feature set 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Group chats (several characters in one chat, ST activation strategies and card-merge modes), tags with folders, favorites and bulk operations on the character list.

**Architecture:** `group/` holds the ST-compatible `Group` model + `GroupRepository` (`groups/<id>.json`, chats in `group chats/<chat_id>.jsonl`), pure activation logic (`GroupActivation`, injectable `Random`) and card merging (`GroupCards`). `tags/` holds `Tag`, `TagMap` helpers and the pure `TagFilter`; tags and the tag map live in `AppSettings` (as in ST's settings.json). `ChatController` gains a group mode: `ChatUiState.group/members`, generation takes an explicit *speaker* so each activated member is generated in turn into its own message (`original_avatar` = `<fileName>.png`, ST-compatible). UI: mixed character/group list with tag filter chips, folders, favorites and multi-select; group editor; tag manager; group chat screen reuses `ChatScreen`.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, coroutines, Compose M3. No new dependencies. JUnit4.

**Spec:** `docs/ROADMAP.md` row 6. Behavior reference (ST repo `/mnt/ide/teamssd/part1/projects/sillytavern_repo/SillyTavern/`): `public/scripts/group-chats.js` (`activate*Order`, `getGroupCharacterCardsLazy`, `generateGroupWrapper`), `public/scripts/tags.js`, `src/endpoints/groups.js`.

## Global Constraints

- Packages under `app.sillytavern.android`: `group`, `tags` (new); edits in `chat/`, `prompt/`, `data/`, `card/`, `command/`, `ui/`. No new dependencies; all 422 existing tests keep passing.
- Persisted formats stay ST-compatible: group file keys `id,name,members,avatar_url,allow_self_responses,activation_strategy,generation_mode,disabled_members,fav,chat_id,chats,auto_mode_delay,generation_mode_join_prefix,generation_mode_join_suffix` (unknown keys preserved); members are avatar file names with extension (`Alice.png`); activation strategy `0 natural,1 list,2 manual,3 pooled`; generation mode `0 swap,1 append,2 append_disabled`; tags `{id,name,folder_type(OPEN|CLOSED|NONE),color,color2,sort_order,create_date,is_hidden_on_character_card}`; `tag_map` keyed by avatar file name / group id; group chat messages carry `original_avatar`.
- Character favorite = `extensions.fav` in the card (and `fav` mirrored at `data.fav`); talkativeness = `extensions.talkativeness` (default 0.5).
- Out of scope: group avatars composed from member images (a plain placeholder is used), group depth prompts, sprites/`hideMutedSprites`, group queue display, tag import/export of settings, drag-and-drop ordering (up/down buttons instead), World Info scanning of non-speaking members' books (only the speaker's sources + global/chat book, as for a single character), auto-continue.
- One commit per task; update `docs/WORKLOG.md` and ROADMAP at the end; stop and report (do not start set 7).

## Review Focus

- Group references a member whose card was deleted/renamed: member is skipped everywhere (activation, merge, UI shows "missing"), nothing throws; a group with zero usable members sends the user message and generates nothing (as ST).
- All members muted: user message is sent, no generation, and a visible notice (not a crash); manual speak on a muted member still works.
- Natural order with a single member who just spoke and `allow_self_responses=false`: still picks someone (ST falls back to random pool from all members) rather than producing an empty list.
- Swipe/continue/regenerate in a group re-generate as the *message's own* speaker (`original_avatar`), and if that member left the group an error is shown and the message is untouched.
- A tag filter with a CLOSED folder hides its members unless the folder tag is selected; deleting a tag removes it from the map and from selections; duplicate tag names (case-insensitive) are reused, not duplicated.
- Bulk delete of characters that are group members leaves groups loadable (member shown missing).
- Chat file / group id sanitising: names with `/` or `..` cannot escape the data dir.

---

### Task 1: Tags model, service and filter

**Files:**
- Create: `tags/Tag.kt`, `tags/TagFilter.kt`
- Modify: `data/Settings.kt` (`AppSettings.tags: List<Tag> = emptyList()`, `tagMap: Map<String, List<String>> = emptyMap()`)
- Test: `test/.../tags/TagTest.kt`

**Interfaces:**
- Produces: `@Serializable data class Tag(val id: String = UUID.randomUUID().toString(), val name: String, val folderType: String = "NONE", val color: String = "", val color2: String = "", val sortOrder: Int = 0, val createDate: Long = 0, val hiddenOnCard: Boolean = false)` (serial names snake_case as in ST).
- Produces: `object TagOps` with pure functions on `AppSettings`-independent `TagState(tags: List<Tag>, map: Map<String,List<String>>)`: `ensure(state, name): Pair<TagState, Tag>` (case-insensitive reuse, trims, rejects blank), `assign(state, key, tagId)`, `unassign(state, key, tagId)`, `rename(state, tagId, newName)` (rejects a name taken by another tag), `delete(state, tagId)` (also from every map entry), `setFolder(state, tagId, folderType)`, `tagsOf(state, key): List<Tag>` (sorted by `sortOrder`, then name), `importCardTags(state, key, cardTags): TagState`, `moveKey(state, oldKey, newKey)`.
- Produces: `object TagFilter { fun apply(entities: List<TagEntity>, state: TagState, include: Set<String>, exclude: Set<String>, favoritesOnly: Boolean, query: String): List<TagEntity> }` with `data class TagEntity(val key: String, val name: String, val fav: Boolean, val cardTags: List<String> = emptyList())`. Rules: include = entity has **all** included tags (ST offers AND/OR logic; we use AND); exclude = has none; CLOSED-folder tag members are hidden unless that tag is in `include`; query matches name or any tag name (case-insensitive).

- [ ] Step 1: tests (`TagTest`): `ensureReusesCaseInsensitive`, `blankTagRejected`, `renameToExistingRejected`, `deleteRemovesFromMap`, `assignIsIdempotent`, `importCardTagsCreatesAndAssigns`, `filterIncludeAnd`, `filterExclude`, `closedFolderHiddenUnlessSelected`, `favoritesOnly`, `queryMatchesTagName`, `settingsRoundTripKeepsTags` (serialize `AppSettings` with a tag, decode, equal; and decode of old settings JSON without the keys works).
- [ ] Step 2: run `./gradlew :app:testDebugUnitTest --tests '*TagTest'` → FAIL (compile).
- [ ] Step 3: implement `Tag.kt` (data + `TagOps`), `TagFilter.kt`, settings fields.
- [ ] Step 4: run `./gradlew :app:testDebugUnitTest` → all PASS.
- [ ] Step 5: commit `feat(tags): tag model, operations and filtering`.

### Task 2: Group model, repository and group chat storage

**Files:**
- Create: `group/Group.kt`, `group/GroupRepository.kt`
- Modify: `data/StoragePaths.kt` (`groups = File(root,"groups")`, `groupChats = File(root,"group chats")`, both created in `ensure()`), `chat/ChatRepository.kt` (group-chat functions), `chat/ChatMessage.kt` (`originalAvatar` accessor), `App.kt` (`val groups = GroupRepository(paths)`)
- Test: `test/.../group/GroupRepositoryTest.kt`

**Interfaces:**
- Produces: `data class Group(val id: String, val name: String, val members: List<String>, val disabledMembers: List<String> = emptyList(), val activationStrategy: Int = 0, val generationMode: Int = 0, val allowSelfResponses: Boolean = false, val autoModeDelay: Int = 5, val joinPrefix: String = "", val joinSuffix: String = "", val fav: Boolean = false, val chatId: String, val chats: List<String>, val avatarUrl: String = "", val raw: JsonObject = JsonObject(emptyMap()))` with `fun toJson(): JsonObject`, `companion fun fromJson(JsonObject): Group` (tolerant: missing keys defaulted, `chats` defaults to `[chatId]`, unknown keys kept in `raw`).
- Produces: `class GroupRepository(paths: StoragePaths)`: `list(): List<Group>` (skips unreadable files, sorted by name), `get(id): Group?`, `create(name: String, members: List<String>, base: Group? = null): Group` (id = `System.currentTimeMillis().toString()` made unique; first chat id = humanized timestamp `yyyy-MM-dd@HH'h'mm'm'ss's'`), `save(group)`, `delete(id)` (also deletes its chat files), `newChatId(group): Group` (adds a chat), `renameKey` not needed. File name `StoragePaths.sanitizeFileName(id) + ".json"`.
- Produces in `ChatRepository`: `groupChatFile(chatId: String): File` (sanitised, `group chats/<chatId>.jsonl`), `createGroup(group: Group, chatId: String, userName: String, greeting: List<ChatMessage>): Chat` (header `{chat_metadata:{}, user_name:"unused", character_name:"unused"}` as ST), `loadGroup(chatId)`.
- Produces: `val ChatMessage.originalAvatar: String?` (from `rest["original_avatar"]`) and `fun ChatMessage.withOriginalAvatar(a: String): ChatMessage`.

- [ ] Step 1: tests: `groupJsonRoundTripKeepsUnknownKeys`, `fromJsonDefaultsMissingFields`, `createWritesFileAndFirstChat`, `deleteRemovesGroupAndChats`, `listSkipsCorruptFile`, `groupChatFileCannotEscapeDir` (id `../../x` stays inside `group chats`), `originalAvatarRoundTripsThroughMessageJson`, `groupChatLoadsAsPlainChat`.
- [ ] Step 2: run `./gradlew :app:testDebugUnitTest --tests '*GroupRepositoryTest'` → FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: run full unit tests → PASS.
- [ ] Step 5: commit `feat(group): group model, repository and chat storage`.

### Task 3: Activation engine and card merging

**Files:**
- Create: `group/GroupActivation.kt`, `group/GroupCards.kt`
- Test: `test/.../group/GroupActivationTest.kt`, `test/.../group/GroupCardsTest.kt`

**Interfaces:**
- Produces: `data class Member(val avatar: String, val name: String, val talkativeness: Double = 0.5)`.
- Produces: `object GroupActivation` (all take `random: kotlin.random.Random = Random.Default`, return **avatars** in speaking order): `natural(members: List<Member>, input: String, last: ChatMessage?, allowSelf: Boolean, isUserInput: Boolean, random): List<String>`; `list(members): List<String>` (distinct); `pooled(members, chat: List<ChatMessage>, last: ChatMessage?, isUserInput, random): List<String>` (one member; ports `activatePooledOrder`: members who haven't spoken since the last user message first, else random excluding the last speaker when >1 member); `manual(members, random)` (one random member; only called when not user input); `swipe(members: List<String>, chat, allowSystem: Boolean, random): List<String>` (speaker of the last non-user/non-narrator message, or the message's own `original_avatar`, falling back to a random member; only avatars in `members`); `impersonate(members, random)` (one random). `extractWords(s): List<String>` = lowercase `\p{L}[\p{L}\p{N}_']*|\p{N}+` matches. Natural order: mentions (a member whose name's words contain an input word, excluding banned speaker `last.name` when !isUserInput && last is non-user && !allowSelf), then talkativeness rolls in shuffled order (`talkativeness >= random.nextDouble()`), then if empty a random pick from members with talkativeness > 0 (all members if none), distinct.
- Produces: `object GroupCards { fun merge(group: Group, members: List<GroupCardMember>, currentAvatar: String, expand: (String, memberName: String) -> String): MergedCard? }` with `data class GroupCardMember(val avatar: String, val name: String, val card: CharacterCard)`; `data class MergedCard(val description: String, val personality: String, val scenario: String, val mesExamples: String)`. Returns `null` for SWAP mode. Per field: skip blank values; skip disabled members unless they are the current speaker or mode is APPEND_DISABLED; each value = `prefix + expand(value.trim()) + suffix` where prefix/suffix have `<FIELDNAME>` (case-insens.) replaced by `Description|Personality|Scenario|Example Messages` and are expanded with that member's name; mesExamples values get `<START>\n` prepended when missing; fields joined by `\n`.

- [ ] Step 1: tests (seeded `Random(1)`/fixed fakes where randomness matters): `naturalMentionActivates` ("hey bob" with Bob+Alice at talkativeness 0 → [Bob]), `naturalBansLastSpeakerUnlessSelfAllowed`, `naturalFallsBackToRandomWhenNobodyRolls` (all talkativeness 0 → exactly 1 member), `naturalSingleMemberJustSpokeStillPicks` (Review Focus), `listKeepsOrderDistinct`, `pooledPicksUnspokenFirst`, `pooledAvoidsLastSpeaker`, `swipeUsesOriginalAvatar`, `swipeSkipsUserAndNarrator`, `swipeMissingMemberReturnsEmpty`, `extractWordsUnicode`; cards: `swapReturnsNull`, `appendSkipsDisabledExceptCurrent`, `appendDisabledIncludesAll`, `fieldNamePlaceholderInPrefix`, `examplesGetStartMarker`, `blankFieldsSkipped`.
- [ ] Step 2: run `./gradlew :app:testDebugUnitTest --tests '*Group*'` → FAIL.
- [ ] Step 3: implement (ports of the cited ST functions; `shuffle` via `random`).
- [ ] Step 4: run full unit tests → PASS.
- [ ] Step 5: commit `feat(group): activation strategies and card merging`.

### Task 4: Prompt integration for groups

**Files:**
- Modify: `prompt/PromptBuilder.kt` (`PromptInput.groupNames: Boolean = false`), `prompt/PromptManagerBuilder.kt`, `prompt/InstructFormatter.kt` only if needed
- Test: `test/.../prompt/GroupPromptTest.kt`

**Interfaces:**
- Consumes: `MergedCard` (Task 3) — the controller applies it by `card.copy(description=…, personality=…, scenario=…, messageExample=…)` before building `PromptInput`, so no prompt-builder signature change is needed for merging.
- Produces: `PromptInput.groupNames`: when true, every history message that is not narrator/system has its content prefixed `"<message.name>: "` (user and assistant), after trimming, for chat completion **and** text completion (in text completion only when instruct `names_behavior` is not applied — i.e. prefix is added in `PromptBuilder.buildText` when `instruct == null`). When `namesBehavior == 2` (existing content-prefix mode) in a group, the per-message name wins over `card.name`. Also the stop string for text completion gets the other members' names? **No** (out of scope) — only the existing `\n{user}:` stop.

- [ ] Step 1: tests: `groupNamesPrefixAssistantAndUserMessages` (chat-completion; history of Alice/Bob/user → contents `"Alice: hi"`, `"Bob: yo"`, `"Me: hey"`), `groupNamesOffLeavesContent`, `groupNamesSkipsNarrator`, `namesBehaviorContentUsesMessageNameInGroup`, `groupNamesTextCompletionWithoutInstruct`.
- [ ] Step 2: run `*GroupPromptTest*` → FAIL.
- [ ] Step 3: implement in `PromptManagerBuilder` (history mapping) and `PromptBuilder.buildText`.
- [ ] Step 4: full unit tests → PASS.
- [ ] Step 5: commit `feat(prompt): speaker names in group prompts`.

### Task 5: ChatController group mode

**Files:**
- Modify: `chat/ChatController.kt`, `App.kt`
- Test: `test/.../chat/GroupChatControllerTest.kt` (use the fake backend from `ChatControllerTest`)

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces: `ChatUiState` gains `group: Group? = null`, `members: List<StoredCharacter> = emptyList()` (resolved, in `group.members` order, missing ones dropped), `groupQueue: List<String> = emptyList()` (avatars pending, for UI). `ChatController` constructor gains `groups: GroupRepository? = null`, `characters: (String) -> StoredCharacter? = { null }` (lookup by file name) and `random: Random = Random.Default`.
- Produces: `fun openGroup(group: Group, chatId: String? = null)` (loads or creates the chat: new chat gets no greeting unless members have `first_mes`: each member's first greeting is added as that member's message, ST behavior when `group.chat_metadata` empty — implement as "all enabled members' first message"); `fun setGroup(transform: (Group) -> Group)` (persists via repo, refreshes members); `fun muteMember(avatar, muted: Boolean)`; `fun speak(avatar: String)` (force one member, works for muted); `fun groupChatIds(): List<String>`; `fun deleteGroupChat(id)`; `newChat()`, `open*`, `chatFiles()` branch on group.
- Behaviour: `send(text)` in a group: adds the user message, then activation per strategy (`isUserInput = true`); strategy MANUAL + user input → no generation. Activated members are generated sequentially, each into a new assistant message named after the member with `original_avatar` set; stop on error or cancel. `regenerate`/`swipe`/`continue` use `GroupActivation.swipe` on the last message; `impersonate` uses the persona (no member speaks: the speaker is a random member only for prompt framing, as ST). If nobody can speak, add nothing and set `notice`. Prompt per speaker: `card` = speaker's card merged by `GroupCards.merge` (macro `{{char}}` = member name), `groupNames = true`. Auto mode: `fun setAutoMode(on: Boolean)` runs `speakNext()` every `group.autoModeDelay` seconds while on and not generating (cancelled by `stop()` and by opening another chat).
- `generating` stays true for the whole batch (no flicker between members): maintain a `batchActive` flag and expose `generating = batchActive || inner generating`.

- [ ] Step 1: tests: `groupSendGeneratesActivatedMembersInOrder` (LIST strategy, two members, fake backend echoing the speaker name → messages Alice, Bob with `original_avatar`), `manualStrategyDoesNotGenerateOnUserInput`, `allMutedSendsUserMessageOnly` (+ notice), `speakForcesMutedMember`, `regenerateUsesMessageSpeaker`, `regenerateOfRemovedMemberErrorsAndKeepsMessage`, `promptHasMergedCardInAppendMode` (capture request: other member's description present), `promptHasSpeakerNamesPrefix`, `errorStopsBatch`, `stopCancelsBatch`, `newGroupChatCreatesFileAndSwitches`, `openGroupWithMissingMemberDoesNotThrow`, and all existing `ChatControllerTest` cases unchanged.
- [ ] Step 2: run `./gradlew :app:testDebugUnitTest --tests '*GroupChatControllerTest'` → FAIL.
- [ ] Step 3: implement. Refactor `runGeneration`/`generateNew` to take `speaker: StoredCharacter? = state.character`; group-specific code lives in a `GroupRunner` private class inside `ChatController.kt`'s package file `chat/GroupSession.kt` to keep the controller from growing further.
- [ ] Step 4: full unit tests → PASS (existing single-character tests untouched).
- [ ] Step 5: commit `feat(chat): group chat generation`.

### Task 6: Group slash commands

**Files:**
- Create: `command/GroupCommands.kt`
- Modify: `command/CommandHost.kt` location (the interface in `command/CommandExecutor.kt` — add defaulted members), `command/CommandLibrary.kt`, `chat/ChatController.kt` (`ControllerHost`)
- Test: `test/.../command/GroupCommandsTest.kt`

**Interfaces:**
- Produces on `CommandHost` (defaults make existing fakes compile): `suspend fun groupMembers(): List<String> = emptyList()` (member **names**, in order), `suspend fun groupEnabled(name: String, enabled: Boolean): Boolean = false`, `suspend fun groupAdd(name: String): Boolean = false`, `suspend fun groupRemove(name: String): Boolean = false`, `suspend fun groupMove(name: String, delta: Int): Boolean = false`, `suspend fun groupSpeak(name: String): Boolean = false`.
- Produces commands: `/member-count`, `/member-get <index|name>` (returns name; index 0-based), `/member-peek <name|index>` → description text, `/member-enable <name|index>`, `/member-disable`, `/member-add <name>`, `/member-remove <name|index>`, `/member-up`, `/member-down`, `/trigger <member name>` extension: if `/trigger` gets an unnamed arg and a group is open it speaks that member. All error with `CommandError("This command needs an open group chat")` outside a group.

- [ ] Step 1: tests with a fake host: `memberCountAndGet`, `memberGetByIndex`, `enableDisable`, `outsideGroupErrors`, `unknownMemberErrors`, `triggerWithNameSpeaksMember`.
- [ ] Step 2: run `*GroupCommandsTest*` → FAIL.
- [ ] Step 3: implement commands + `ControllerHost` overrides.
- [ ] Step 4: full unit tests → PASS.
- [ ] Step 5: commit `feat(command): group member commands`.

### Task 7: Character fields (fav, talkativeness) and card-tag import

**Files:**
- Modify: `card/CharacterCard.kt` (`val fav: Boolean`, `val talkativeness: Double` computed from `raw`; `fun withFav(Boolean)`, `fun withTalkativeness(Double)` writing `extensions.*` and `data.fav`/`talkativeness` mirror), `card/CharacterRepository.kt` (`setFav(fileName, Boolean)`), `ui/CharacterEditScreen.kt` (talkativeness slider 0..1, favorite switch, tag chips editor using the shared `TagChips` composable from Task 8 — until then plain comma field stays)
- Test: `test/.../card/CardFieldsTest.kt`

- [ ] Step 1: tests: `favDefaultsFalseAndRoundTrips`, `talkativenessDefaultsHalf`, `talkativenessStringInV1StyleCardParsed` (`"talkativeness":"0.8"` at top level and in `extensions`), `unknownExtensionsKeptWhenSettingFav`, `invalidTalkativenessClamped`.
- [ ] Step 2: run `*CardFieldsTest*` → FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: full unit tests → PASS.
- [ ] Step 5: commit `feat(card): favorite and talkativeness fields`.

### Task 8: UI — list, tags, groups

**Files:**
- Create: `ui/GroupEditScreen.kt`, `ui/TagManagerScreen.kt`, `ui/TagWidgets.kt` (`TagChips`, tag picker dialog)
- Modify: `ui/CharacterListScreen.kt`, `ui/ChatScreen.kt`, `MainActivity.kt`, `ui/SettingsScreen.kt` (link to tag manager)
- No unit tests (UI): compile + lint only; logic is in Tasks 1–6.

Scope:
- **List**: characters and groups in one list (groups with a "group" badge and member count), favorites first with a ★ toggle, search box (name + tags), tag filter chips row (tap = include, long-press = exclude, uses `TagFilter`), "favorites only" chip, folder tags shown as chips that open/close as in ST, `+` menu: new character / new group.
- **Bulk**: long-press enters selection mode; app bar actions: select all, favorite/unfavorite, add tag, remove tag, delete (confirm), "Create group from selection".
- **Group editor**: name, member list (up/down/remove/mute toggle, shows "missing" for absent cards), add-member picker, activation strategy and generation mode dropdowns, allow self responses, auto-mode delay, join prefix/suffix (shown for append modes), favorite, tags, chats list, delete group.
- **Tag manager** (Settings → Tags): list with rename, folder type (None/Open/Closed), hide-on-card, delete, create.
- **Chat screen in group mode**: title = group name; member strip (name chips: tap = make that member speak, long-press = mute/unmute, muted dimmed); message bubbles already show `name`; menu items "Edit group", "Auto mode", chats dialog uses group chats; character-only items hidden.
- Navigation: `groupchat/{id}` (opens `ChatScreen` via `openGroup`), `groupedit/{id}`, `tags`.

- [ ] Step 1: implement composables and navigation (reuse `Field`, `Pick` from `ui/Common.kt`).
- [ ] Step 2: run `./gradlew :app:compileDebugKotlin` → success.
- [ ] Step 3: run `./gradlew :app:lintDebug` → no errors.
- [ ] Step 4: run `./gradlew :app:assembleDebug` → APK builds.
- [ ] Step 5: commit `feat(ui): groups, tags, favorites and bulk operations`.

### Task 9: Review, docs and sign-off

**Files:** `docs/WORKLOG.md`, `docs/ROADMAP.md` (mark row 6 DONE), this plan (tick boxes).

- [ ] Step 1: run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → all green; record the test count.
- [ ] Step 2: self-review against the Review Focus list (each line has an owning test above; add any missing).
- [ ] Step 3: append "Done (feature set 6)", "Rulings / deviations (set 6)" and "Not verified (set 6)" to the worklog; update ROADMAP row 6.
- [ ] Step 4: commit `docs: work log and roadmap for set 6 (groups + tags)`; stop and report (do not start set 7).
