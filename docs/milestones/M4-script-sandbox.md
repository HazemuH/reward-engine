# M4 — Groovy script sandbox (hardened)

**Status: TODO**

Only needed once a rule actually requires a script — most don't (see `.claude/skills/rule-authoring`). Can trail M2/M3 as long as `CampaignVersion.optionalScript` is never populated until this lands.

Runtime decided in open-decisions #24: Groovy, hardened against RCE. The threat is a script — written by a compromised or malicious admin, or slipped past review — escaping into the JVM. No in-JVM sandbox is provably escape-proof, so this is layered: each ticket closes a known class of escape, and RE-036 proves it with real payloads. The Java Security Manager is gone (JEP 486), so older "Groovy sandbox" guides that rely on it don't apply.

| Ticket | Description | Status | Depends on |
|---|---|---|---|
| RE-030 | **Static compilation + call allowlist.** Compile every script with `@CompileStatic` (via `ASTTransformationCustomizer`) plus a type-checking extension that rejects any method call, property access or constructor not on an explicit allowlist (receiver type + member). Dynamic dispatch is what makes `SecureASTCustomizer` alone bypassable (`"cmd".execute()`, `this.class.classLoader`, `metaClass`, GString method names); with static compilation every call is resolved and checked at compile time | TODO | M2 |
| RE-031 | **Nothing runs at compile time.** Reject any annotation in script source (closes `@ASTTest` / `@Grab` — the known compile-time RCE path); `setDisabledGlobalASTTransformations` for all global transforms; Grape disabled (`groovy.grape.enable=false`); `SecureASTCustomizer` as a second net: no imports outside the allowlist, no package, no class or method declarations, indirect-import check on | TODO | RE-030 |
| RE-032 | **Isolated classloader.** Compile and run in a `GroovyClassLoader` whose parent exposes only the allowlisted classes (java.lang basics, `facts`/`vars`/`ref`, the `Decision` builders) — Spring beans, JDBC drivers and the app's own classes aren't reachable even by name. One compiled class per campaign version, cached; dropped when the version is superseded | TODO | RE-030 |
| RE-033 | **Decision contract.** Script returns `{ eligible, benefits[], consumes[] }` through allowlisted builders (`reward()`, `benefit()`, `consume()`, `none()`); engine validates benefit types and counter names against the version's own declarations and rejects anything undeclared | TODO | RE-030 |
| RE-034 | **Execution limits.** 50ms via `TimedInterrupt` (+ `ThreadInterrupt`) injected by the compiler configuration — never by the script — into every loop and method entry; run on a bounded executor; exception, interrupt or timeout → not eligible, no benefit, `reward.script.failure` metric. Memory can't be capped in-JVM: bounded instead by the time limit and by keeping collection-building calls off the allowlist | TODO | RE-030 |
| RE-035 | **Read-only inputs.** `facts`/`vars`/`ref` exposed as immutable views; `facts.now` is the only clock | TODO | RE-033 |
| RE-036 | **Bypass test suite — the acceptance gate.** Each of these must fail at compile time (or, for the last two, be stopped by RE-034) with a test per payload: `"id".execute()`, `Runtime.getRuntime()`, `new ProcessBuilder(...)`, `Class.forName(...)`, `this.class.classLoader`, `getClass().getMethods()` / any `java.lang.reflect`, `metaClass` tricks, method name from a GString (`"$x"()`), `Eval.me(...)`, `new GroovyShell()`, `@Grab`, `@ASTTest`, `System.exit`, `System.getenv`, file and socket access, `Thread`, `while(true) {}`, `Thread.sleep`. New public Groovy sandbox escapes get added here as they're published | TODO | RE-030–RE-035 |
| RE-037 | **Defense in depth outside the JVM.** `core` connects with a least-privilege DB user (no DDL, no superuser); no outbound network except what's allowlisted; container runs non-root on a read-only filesystem. If something escapes anyway, it lands somewhere it can't do much. Out-of-process script execution stays an option if a tenant's threat model demands it | TODO | — |
| RE-038 | **Authoring workflow.** Script compiled and its test cases (zero amount, at-cap, missing attribute) run at save time; a version with a failing or missing test can't be submitted; maker ≠ checker approval before activation | TODO | RE-033, M7 |
| RE-039 | **Dry-run / simulate** — reuses the `calculate` path; no write path exists for scripts even under simulation | TODO | RE-033, M2 |

## Exit criteria

- Every payload in RE-036 is rejected, each by its own test — not just "the happy path still works".
- A script that throws or times out mid-execution never grants a benefit — covered by test, not just by the `try/catch` existing.
- A `security-review` pass over the sandbox code before the first script-using campaign goes live.
