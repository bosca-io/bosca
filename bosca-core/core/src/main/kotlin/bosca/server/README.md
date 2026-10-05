# Bosca HTTP Server

Bosca's HTTP server API was inspired by Ktor. We built it primarily because Ktor
did not support our GraalVM and Netty setup at the time, and we had encountered
performance issues in some of our use cases.

We may eventually introduce shared interfaces to support interchangeable server
implementations, including Ktor, Vert.x, and Bosca HTTP. For now, the current
implementation meets our needs.

Ktor remains an important part of our stack. We continue to use many of its
components and value the functionality they provide.

Once those interfaces are in place, we intend to extract Bosca HTTP into a
standalone project and release it under the Apache 2.0 license. We have not yet
had time to separate it from the platform, but hope to do so soon.