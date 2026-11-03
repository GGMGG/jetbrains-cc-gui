# Claw IDE client

This topic adds the IDE-side facade over protocol 2 without attaching it to
chat tabs or starting an external channel. Registration snapshots are bounded,
reject duplicates and preserve instance/connection ownership. A lost gateway
recovers the entire owned roster before resuming requests; a newer owner epoch
cannot be overwritten. Accepted values must be JSON booleans and response
actions must match the operation as well as the transport request identity.

Close cancels heartbeat work and interrupts active socket IO immediately. It
performs no network cleanup that could block the IDE or unregister a replacement
binding. Normal tab removal still unregisters explicitly; abandoned rows expire
under the registry's 30-second heartbeat lease.

Real loopback tests exercise complete reconnect, stale owners, malformed results,
duplicate snapshots and closing during blocked IO. No provider, account or live
configuration is used.
