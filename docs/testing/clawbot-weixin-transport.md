# Weixin transport boundary

This topic introduces the isolated Node iLink adapter and its owned Java subprocess facade. It does not activate an account or register IDE handlers.

- Pairing and HTTP requests use the validated iLink contract and bounded polling/retry rules. Public error details contain only numeric status information.
- The daemon accepts at most 64 pending requests and 256 KiB per UTF-8 frame. It rejects oversized partial lines before allocating an entire line and bounds outgoing frames.
- The Java facade correlates replies with the originating child process, caps pending requests, and applies deadlines before writing to a pipe. Shutdown interrupts startup, pending reads, and blocked writes.
- No child stderr or provider response text is copied to diagnostics. Credentials are passed only through the private owned process protocol.

Validation: `npm run test:weixin` in `ai-bridge`; focused `ClawBotIlinkProcessTest` and the ClawBot Java package plus Checkstyle. Fixtures launch Node locally and never call an external provider or read the user's Codex configuration.

Account pairing and actual Weixin delivery require an explicitly configured account and are outside these offline checks.
