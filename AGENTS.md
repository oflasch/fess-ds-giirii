# AGENTS.md — Fess GiIRiI Data Store Plugin

This is a **codelibs Fess** data store plugin for crawling official websites
containing German federal law. Runtime target is **Java 21** platform.

## Build & verify

```bash
mvn clean compile           # compile
mvn test                    # run tests (DI-container wiring tests)
mvn clean package           # plugin uber-JAR + theme ZIP in target/
mvn impsort:sort formatter:format license:format   # ALWAYS run before committing
```

Every change to `src/main/java` must keep `mvn test` green and be run through
`impsort:sort` + `formatter:format` + `license:format` (CI style is non-negotiable — do not
hand-format).

**The Javadoc must build without warnings.** `mvn clean package` runs Javadoc and
lists what it finds under `[WARNING] Javadoc Warnings`; look for that heading after
every change to `src/main/java` and fix each entry before committing. The usual
one is a public class with no explicit constructor ("use of default constructor,
which does not provide a comment"): give it a documented one, as every DI
component here already has.

The license header lives in `etc/license-header.txt` and is applied by a
deliberate `combine.self="override"` of the inherited plugin config: fess-parent
would otherwise stamp FEDR's sources with the CodeLibs copyright and fetch the
header text from codelibs.org on every build. **`license:format` only adds a
missing header, it never rewrites an existing one** — so after editing
`etc/license-header.txt`, run `mvn license:remove license:format` to restamp.

## Git

- **`main` is the only branch you commit to.** Directly. No feature branches, no
  pull requests, no merges. There is a second branch, `public`, which is
  generated and never edited by hand — see below.
- **A commit message is prose.** A short subject line, then a body saying what
  was wrong, what the change does and why that way, and a closing line saying how
  it was verified.
- **No attribution trailers, ever.** No `Co-Authored-By:`, no `Claude-Session:`,
  no "Generated with" line, and no naming of a tool or model anywhere in the
  message. This history is the project's, not a record of how it was typed.
- **Tags are semantic versions** — `vMAJOR.MINOR.PATCH`, annotated, one per
  release, with a message in the same register as a commit body. Nothing in the
  tree carries the version; the tag is the only place it lives.
- **Pushing a tag releases.** `.github/workflows/release.yml` publishes tag
  `vX.Y.Z` to Maven Central as version `<Fess version>-X.Y.Z` and creates the
  GitHub release from the tag message. The Fess version is the version of the
  parent `fess-parent`. The tag must be annotated and on `main`. A manual run
  of the workflow is a dry run. You must never move a pushed tag: a failed
  release is fixed on `main` and shipped under the next patch version. When the
  job `github-release` fails, use "Re-run failed jobs"; a full re-run is
  rejected by Central, which accepts each version once.

## Style of documentation

**The register is a specification, not an essay.** It states, obliges and
qualifies. It does not argue, point or comment on itself. Nearly every
correction below is an instance of that one difference.

* **No Meta-Commentary:** Cut self-referential document statements.
  * [X] *This is the central property, and it is verifiable.* -> [OK] State the property directly.
* **No Trailing Aphorisms:** Replace punchlines and rhetorical inversions with plain facts.
  * [X] *Citations are attached so you notice them.* -> [OK] *Verifying citations is essential.*
* **Positive Definitions ("A", not "A, not B"):** Omit contrast against imaginary alternatives.
  * [X] *RAM is a requirement, not a recommendation.* -> [OK] *RAM is a mandatory requirement.*
  * [X] *FESS reads from A, not B.* -> [OK] *FESS reads from A.*
* **No "Not Only..., But Also...":** State multiple facts directly.
  * [X] *FEDR offers not only search, but also AI.* -> [OK] *FEDR provides search and AI tools.*
* **No Dramatic Em-Dashes (`--`):** Connect related statements neutrally.
  * [X] *Overrides settings -- for good reason.* -> [OK] *Overrides settings to ensure consistency.*
* **Cut Didactic Fluff:** Omit *Important to note:* or *Crucial here is:*. Start directly with subject/action.
* **No Conversational Fillers:** Omit *simply*, *actually*, *basically*, *so to speak*, *indeed*.
  * [X] *Script basically checks if server is actually reachable.* -> [OK] *Script verifies server reachability.*
* **Do Not Anthropomorphize:** Software has no desires or intentions.
  * [X] *Script wants / Service tries to...* -> [OK] *Script executes / Service requests...*
* **Introduce Lists by Subject, Not Count:**
  * [X] *There are two options, pick one prior to setup.* -> [OK] *FEDR supports two deployment models:*
* **Use Explicit Imperatives (`must`):** Frame obligations as duties, not habits.
  * [X] *Anyone deciding reads the source.* -> [OK] *You must read the source before deciding.*
* **Negate Capability (`cannot`), Not Intention (`will not`):**
  * [X] *Not set up for this purpose.* -> [OK] *Cannot be configured for this purpose.*
* **Mark Temporary Limits as Current:**
  * [X] *Custom mapping is not supported.* -> [OK] *Custom mapping is currently not supported.*
* **Pair Limits with Alternatives:** Never leave a limitation stated in isolation.
  * [X] *Single nodes cannot process >10TB.* -> [OK] *Single nodes cannot process >10TB; however, cluster operation supports large-scale deployments.*
* **Use Precise Technical Terms & Anglicisms:** Use exact terms of art (`conversation` -> `AI chat` | `machine` -> `virtual server` | `in-house` -> `on-premises`).
* **Scope Absolute Guarantees:**
  * [X] *No user data will be used.* -> [OK] *No user data will be processed without consent.*
  * [X] *Your data stays with you.* -> [OK] *All data remains in your sole ownership and possession.*
* **State Purpose Before Architecture:**
  * [X] *Uses a distributed vector index...* -> [OK] *FEDR is an enterprise search engine for knowledge management. It uses a distributed vector index...*
* **Name Actor and Action:** Avoid passive, autonomous phrasing.
  * [X] *This notice disappears when complete.* -> [OK] *The system removes this notice once verified.*
* **Consistency Over Variation:** Retain exact technical terms across adjacent sentences; never swap terms merely for stylistic variety.

## Java style - modern, but Fess-idiomatic

- **Simple and straightforward wins.** Reach for the plainest solution that is
  correct; add abstraction only when a second caller actually needs it. No
  frameworks, patterns or dependencies the task does not demand.
- **Prefer stateless and functional** — pure methods, immutable data,
  `Stream`/`Optional` where they read clearly. Back off to plain loops and
  mutable locals when they are simpler, faster, or more readable. Never trade a
  hot path or an obvious loop for a clever pipeline.
- **Immutability by default.** `final` on every field, parameter, and local that
  is not reassigned (matches existing code). Model request-scoped data as
  immutable snapshots (see `agent/FessContext`) rather than passing mutable
  context around.
- **Typesafe, no raw types.** Parameterize generics fully; prefer enums and
  small records over stringly-typed flags. Use `var` only when the initializer
  already names the type.
- Modern Java is welcome where it aids clarity: `switch` expressions, text
  blocks, records, pattern matching, `List.of`/`Map.of` for small immutables.
- Java 21 syntax level; do **not** introduce preview features or a JPMS
  `module-info` (the shade build strips module descriptors).
- **Javadoc every class and method, public, protected and private**, `@author
  Oliver Flasch`, `@param`/`@return`/ `@throws`. Keep the numbered `// 1. … //
  2. …` step comments for multi-stage methods.
- Package root is `de.oliverflasch.fedr`; keep the `agent` / `api` / `helper`
  split.

## Code hygiene

Dead and stray code matters more here than in an ordinary application. This is a
system whose whole job is to show one user exactly the documents they are allowed
to see; code that nobody calls is code nobody tests and nobody reads closely, and
a later change can wire it back into a live path still carrying assumptions that
stopped being true. Treat the three below as defects, not as tidying.

- **Delete what your change orphans.** After removing a caller, check whether it
  was the last one. 
- **Nothing in `src/main` writes to stdout or stderr.** No `System.out`, no
  `printStackTrace`. Both bypass the guarded, parameterized Log4j2 logging the
  rest of the code uses, and a stack trace *printed* rather than logged is the
  easiest way to put internals somewhere nobody reviewed — which is the rule in
  "Never leak internals to clients", one layer down. Debugging output added
  during a session goes out before the commit. The tree is clean today; keep it
  so.
- **Visibility is API surface, so widen it deliberately.** `private` until a real
  caller needs otherwise, and a method made public must not drag a private type
  into the open with it.

## Dependency injection & reusing Fess

- Components are LastaDi beans registered in `src/main/resources/fess_api++.xml`
  (additive `++` merge). Inject collaborators with `@Resource` by field name; do
  one-time setup in `@PostConstruct`.
- **Reuse Fess core, never copy it.** Resolve core services through
  `ComponentUtil.getXxx()` (e.g. `getRankFusionProcessor()`,
  `getSearchLogHelper()`, `getFessConfig()`, `getV2EnvelopeWriter()`). Read
  tunables from `FessConfig` (`getOrDefault(key, default)`), namespaced under
  `fedr.*`.
- Handlers/managers are shared singletons — keep them **stateless and
  thread-safe**. Hold `ObjectMapper` and similar as `private static final`
  constants.

## Logging

- Log4j2: `private static final Logger logger =
  LogManager.getLogger(Xxx.class);`
- Guard non-trivial messages: `if (logger.isDebugEnabled()) { logger.debug(...);
  }`
- Use parameterized messages (`logger.info("built {} model", tier)`), never
  string concatenation in the call.

## Defensive coding — security, reliability, robustness

- **Always honor document ACLs — and know what actually enforces them.**
  Passing the caller's `FessUserBean` into `RankFusionProcessor` does *not* apply
  role filters; it only selects an OpenSearch shard preference and attributes the
  search log. See the caveat below. Record agent-initiated searches through
  `SearchLogHelper.addSearchLog`, *inside* the role scope, and never through
  `ActivityHelper` — that one is Fess's security trail (logins, permission
  changes), not a place for tool calls.
- Null-check optional context (`FessUserBean`, request), fail closed, and prefer
  `OptionalThing`/`Optional` over returning `null` from new APIs.
- Don't log secrets (API keys, tokens) or full document contents.
- **Trust nothing from the wire.** Validate/deserialize request bodies
  explicitly; reject unknown modes and missing required fields with a typed
  error, not a default that silently changes behavior.
- **Never leak internals to clients.** Return generic messages via the v2
  envelope writer / `AgentSsePayload`; log the real cause server-side. Do not
  put `e.getMessage()` from upstream libraries on the response.
- **An uploaded file is extracted, never stored.** What FEDR persists is
  sanitized *text*; the bytes the client sent live in a local variable and are
  unreachable once the request returns — no field, no index, no disk, no log, no
  support bundle (M11 §2). A change that puts a `byte[]` on a stored record, or
  base64 in a payload, is a defect and `AttachmentBytesAreNeverStoredTest` is
  what says so. Sanitization happens once, at the boundary, in
  `AttachmentText.sanitize`: a second pass further in invites the belief that the
  first was optional.
- **Preserve the security baseline** in `FedrApiManager`: `Cache-Control:
  no-store`, Origin check + session CSRF token on unsafe methods,
  `login.required` gate for anonymous callers. Any new endpoint goes through the
  same checks.

### Caveat: Fess resolves document ACLs from a thread local, and fails open

This one has already caused a real disclosure bug, so treat it as a standing rule
for **every** code path that reaches the index.

The role filter comes from exactly one place, `RoleQueryHelper.build(type)`, which
reads the servlet request out of Lasta Di's `ExternalContext` — a **ThreadLocal**.
The `OptionalThing<FessUserBean>` you pass to `RankFusionProcessor.search(...)` is
never consulted for access control. And `QueryHelper.buildRoleQuery` only adds a
role clause `if (!roleSet.isEmpty())`, so **no request in scope ⇒ empty role set ⇒
no filter at all ⇒ every document in the index matches**, including ones the
caller has no role for. It fails *open*, silently.

Agent tools run on LangChain4j worker threads, where no request is in scope. So:

- Capture the caller's roles on the servlet thread (`FessContext.getSearchRoles()`,
  via `RoleQueryHelper.build(SearchRequestType.SEARCH)`) and re-bind them around
  the search with `FedrRoleScope.bind(roles)` in try-with-resources.
- **Refuse to search on an empty role set.** Fail closed; never inherit Fess's
  fail-open default.
- `FedrRoleScope` binds a *detached* request stand-in, not the live one. Fess's own
  `RankFusionProcessor` re-binds the real request across its executor, but FEDR
  cannot: an abandoned chat stream keeps running after the response completes, by
  which point Tomcat has recycled the request object.
- `bind` verifies the roles actually reached Fess's resolution and throws if not, so
  a rename of the internal `userRoles` attribute surfaces as a refused search rather
  than as disclosure.

To check this in dev: `docker logs fess01 | grep roleSet`. The search UI logs
`roleSet: [1guest, Rguest]` on an `http-nio-8080-exec-*` thread; anything logging
`roleSet: []` on a pool thread is unfiltered and is a bug.

## Concurrency

- The servlet thread owns the `HttpServletRequest` and LastaFlute web-scope;
  LangChain4j callbacks run on worker threads. Capture what you need into an
  immutable snapshot on the servlet thread (`FessContext` pattern) before
  crossing that boundary. Anything Fess reads from the request thread local —
  document roles above all — must be resolved into that snapshot, never lazily on
  the worker thread.
- Guard shared streams (`synchronized (writer)` in the SSE handler). Bound every
  wait (`latch.await(timeout, …)`); never block a request thread indefinitely.

## Tests

- **Pure unit tests** (plain JUnit 5, no container) carry most of the load:
  validation, error mapping, truncation, SSE frame formatting. Fast, no DI.
- **DI wiring tests** (`UnitWebappTestCase`, utflute + LastaDi) prove the
  `fess_api++.xml` registration — resolve the component by name **the way Fess
  does at runtime** and assert it is there. One per component; keep behavior
  assertions out of this tier.
- Add a test for every new component and every new API sub-path.
- **Assertions are JUnit 5 (Jupiter), not 4** — `import static
  org.junit.jupiter.api.Assertions.assertEquals` and friends, `assertX(expected,
  actual, "message")` with the message **last** and always a plain `String`,
  never a format string. `junit:junit:4.13.2` is on the classpath too, but only
  because utflute's `UnitWebappTestCase` base needs it; nothing above that base
  should import from `org.junit.Assert`. Writing the JUnit 4 order —
  `assertX("message", expected, actual)` — compiles as a call to a
  nonexistent overload and fails with a message like "Methode für
  assertEquals(String,int,int) nicht geeignet" pointing at the assertion line,
  not at the real mistake.

**Verifying plumbing is not verifying behaviour.** Sequence numbers, ACL bindings
and re-attach can all be provably correct while the agent has been lobotomised.
End-to-end checks should include at least one question only the system prompt can
answer — *"What is your name?"* should say FEDR, and a question about the
archive should visibly search — because those two cost seconds and fail loudly
when the memory contract has been broken.

Outside Java there are two more tiers, and they follow the same split — the
fast one carries the load and needs nothing:

**`mvn test` must pass offline: no API key, no Docker, no network.** Never let a
test reach a real LLM or a real OpenSearch — they cost money, need secrets, and
are nondeterministic. With no `fedr.llm.*` configured, `FedrLlmHelper` reports
`isConfigured() == false` and the container still boots, so tests need no LLM
config at all; LangChain4j model builders perform no I/O, so building a model is
fine, calling `chat()` is not. Stub core collaborators via
`ComponentUtil.register(...)`.

**DI test harness — do not rearrange it casually.** `test_app.xml` includes core's
`fess_api.xml`, and LastaDi then merges this plugin's `fess_api++.xml` into *that
same container*. That merge is what Fess performs at runtime, and it is the only
arrangement in which FEDR's `@Resource` fields bind to core collaborators such as
`originValidator`: `@Resource` binding does not search parent or sibling
containers, so an explicit `<include>` of a `++` file silently creates a second,
unbindable container. Two supporting settings keep this working offline — a
test-scoped `tomcat-embed-core` (core components reference `ClientAbortException`)
and surefire's `fess.conf.path` pointed into `target/`, so no `WEB-INF` tree is
written into the project root.

**Security invariants. Each needs a test, and every new endpoint re-tests the
ones it touches:**

1. **ACL propagation** — the roles captured on the servlet thread are the ones
   Fess filters by *on the worker thread the tool actually runs on*, so exercise
   the tool off-thread (`FessSearchToolTest.searchOnAWorkerThread`); an empty role
   set is refused, not searched; and an anonymous caller passes
   `OptionalThing.empty()`, never a privileged default.
2. **Tenant isolation** — two owners sharing a `conversation_id` get separate
   chat memories, and no crafted id can reach another owner's namespace.
3. **No disclosure** — see the canary rule below.
4. **Fail closed** — invalid input is refused with a typed error, never
   defaulted into something that silently changes behavior.
5. **Bounded** — every cap (body, prompt, id length, conversation count,
   snippet, timeout) is *enforced*, not merely configured.
6. **Gated** — unsafe methods require CSRF; anonymous callers are refused when
   `login.required` is set.
7. **No frame injection** — crawled document text (titles, snippets) flows into
   SSE payloads. It must not be able to forge frame or event boundaries.

**Canary rule.** For anything that must not reach a client, seed the input with a
unique marker — `"CANARY-<random>"` in an exception message, a config value, a
document body — and assert the marker appears nowhere in the serialized
response. One cheap assertion that catches whole classes of leak regression.
