# Read-only Codex session relationships

This patch depends only on storage inventory. It displays parent identity,
saved parent titles, missing-parent status, nested subagent status and direct
child counts in the session list and content view. It introduces no maintenance
or deletion command.

Relationships come from native state edges or explicit rollout metadata. The
whole inventory supplies parent information before pagination and filtering;
titles are never used to infer a relationship. Duplicate archive headers do not
double-count children. Parent titles are rendered as ordinary React text.

## Validation

- 21 focused Java cases passed with Checkstyle, covering inventory and lineage.
- 46 affected frontend cases passed; test TypeScript compilation and production
  build passed.
- Fixtures include parents outside the selected page or archive filter, renamed
  parents, nested subagents, missing and database-only parents, authoritative
  native edges, duplicate archive records and self references.

All checks use disposable fixtures. No live IDE rendering is claimed.
