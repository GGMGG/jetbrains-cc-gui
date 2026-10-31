# Native live-page integration

The existing Office window lease, navigation policy and private preferences remain the authority for this page. A separate strict command vocabulary connects complete renderer transfers, background storage and immutable art.

- Exact existing-tab navigation keeps its original three-second deadline, including time spent in the native worker queue.
- Scene refresh is coalesced; two art transfers and two storage requests may be outstanding. Art capacity remains occupied until the EDT finishes its bounded frame batches.
- Full shared preference updates pass through the same revision check, EDT acknowledgement and rollback used by native window settings. Background storage cannot bypass this controller.
- Closing or replacing the page retires all callback authority before native disposal; storage cleanup and final geometry persistence run outside the EDT.
- The frontend activation and visual themes are delivered separately. The current local page remains buildable at this step.

Validation: focused Office Java package, floating-pet regression tests and Checkstyle, including delayed window acknowledgement, rollback, retired callbacks, scene acknowledgement and art backpressure. Real IntelliJ/JCEF window behavior still requires an interactive IDE acceptance run.
