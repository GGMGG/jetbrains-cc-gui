# Complete scene and image model

This frontend topic provides typed scene decoding, acknowledged transfer assembly, bounded image decoding/cache, native background storage and the reusable canvas renderer. The production entry point remains the existing local Office page.

The parser retains every backend phase and validates waiting ownership. Foreign sources require an explicit participant roster; peer project IDs and recent-result identities remain independent. Interrupted transfers and late asynchronous hashes never replace the last complete scene.

Images are admitted by actual format/dimensions/frame count, decoded within a shared byte budget, and released when no longer demanded. Two concurrent image requests are allowed. Static backgrounds share the same memory budget and use chunked native persistence rather than renderer paths.

Validation: focused `src/petOffice` Vitest suites, test TypeScript compilation, and the webview production build. Scene fixtures are test inputs and are never imported by the live page entry point.
