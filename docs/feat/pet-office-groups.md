# Employee groups

Groups organize currently connected IDE employees into offices. They do not create agents, start tasks, or configure providers.

## Using groups

- The compact group switcher is part of the scene panel. Select a group to see its employees together in one office.
- **Manage groups** opens one editor. Enter a name to create a group, rename or delete existing groups, and choose a group beside each online employee.
- A group has at most twelve online employees and never paginates. Full groups are disabled in assignment selectors. Ungrouped employees retain the existing floor navigation.
- The selected workstation also provides compact **Group** and **Desk** selectors. Desk moves and swaps stay within the same group. Mouse users can drag between desks; ungrouped employees can exchange desks across their existing floors.
- Groups use the scene and background selected in the main toolbar. There are no per-group scene settings, emblems, project rules, or offline-member management screens.
- Offline employees disappear from the editor and free their seats. Their membership is remembered. When a member returns to a full office, they appear in Ungrouped until a seat becomes available. Existing online colleagues keep their places.

## Persistence and compatibility

Preferences store ordered group names, the selected group, and bounded binding-scoped membership and preferred-desk memories. A preferred desk is not an offline reservation. Actual online seats are allocated uniquely within each room, with priority for employees already present.

Older group names and memberships migrate automatically. Obsolete emblem, scene, and project-rule settings are discarded. Identities still include the full instance, tab handle and binding epoch, so a reused tab never inherits another employee's group or name.

At most 256 binding memories are retained. A new assignment can replace dormant memories at this limit, while current online bindings remain protected. Removing a group clears its remembered memberships and returns connected members to Ungrouped.

Native and webview validation enforce the same field, name, reference and preferred-desk bounds. Preferences remain limited to 48,000 UTF-8 bytes and the 64 KiB outer message frame. Metadata byte accounting matches native Gson escaping. Name and Pet metadata share a budget and reclaim retired data together, preserving remembered group members.

## Verification

Model tests cover migration, offline capacity, full-room return and recovery, bounded memory, persistent seats, atomic assignment limits, binding reuse and cross-group rejection. Native tests cover the reduced schema and preferred-desk memories. Browser checks cover the single editor, shared scenes and backgrounds, live-only lists, seat swaps, save failure recovery and responsive layouts.
