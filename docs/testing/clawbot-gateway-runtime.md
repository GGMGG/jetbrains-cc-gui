# Gateway runtime composition

This topic composes the session/IPC, IDE client, binding/access, routing, progress, encrypted delivery and isolated Weixin transport topics. It introduces the application service without registering GUI handlers or startup hooks.

The gateway holds one desktop leader lease, publishes sanitized follower status and takes over after lease loss. Discovery schema version 1 is independent of authenticated IPC protocol version 2. Followers construct transport endpoints with the wire version, not the discovery version.

Inbound execution claims and delivery outcomes are durable. Unknown sends are never replayed automatically. Explicit retained-reply recovery requires the current binding, authorization and exact target generation. Native interaction, preview and control delivery retain their route revisions and concurrent admission checks.

Transport failures cross IDE IPC only as bounded `CLAWBOT_` or `ILINK_` codes. Raw provider error text is rejected. Credentials and recipient/context/text are absent from public status and unencrypted receipts.

Validation: all ClawBot Java package tests and Checkstyle, including multiple real authenticated loopback clients, leader/follower discovery, ownership replacement, concurrent interaction/control routing, ambiguous sends and explicit recovery. Offline transport fixtures exercise the real daemon response parser without provider calls.

An account pairing or end-to-end Weixin send is not claimed by these offline checks. GUI activation is a separate topic.
