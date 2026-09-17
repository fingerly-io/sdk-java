rootProject.name = "fingerly-java"

// The core carries every decision. The adapters only translate between one
// server API and the core's own request and response types, because Java has
// no single HTTP server standard the way Go has net/http.
include("fingerly-server")
include("fingerly-server-jakarta")
include("fingerly-server-javax")
include("fingerly-server-webflux")
