# Configurable Sequential Video Gateway

This client has **no default endpoint** and does not claim that any live Dola service implements this protocol. It only runs after a user imports a job that names an HTTPS origin they control or are authorized to use. The app does not copy browser cookies/accounts. API tokens are memory-only.

## Job import JSON

```json
{"schemaVersion":1,"sequenceId":"story-20261012","gateway":"https://video-gateway.example","segments":[{"model":"provider-model","prompt":"first scene","referenceImages":["https://video-gateway.example/refs/a"],"params":{"duration":"5","ratio":"16:9"}},{"model":"provider-model","prompt":"second scene","referenceImages":[],"params":{}}]}
```

`sequenceId` is `[A-Za-z0-9_-]` (max 80). 1–100 segments are executed in listed order. Each reference is preserved; the previous segment's extracted last-frame reference is prepended only for the next request. `params` values retain their JSON types (objects, arrays, booleans, numbers, null, and strings); they are serialized internally then decoded immediately before the POST. `model`, `prompt`, and original references are sent unchanged. Job files must not contain tokens.

## Gateway contract (user-provided server)

All paths are relative to the imported HTTPS origin. Redirects are disabled. Authorization is sent only to that exact origin; result downloads on a foreign HTTPS origin receive no token. Responses are bounded and IDs/statuses are validated.

* `POST /jobs` — JSON `{index,model,prompt,referenceImages,params}` with `Idempotency-Key`. Return `{"id":"safe-id"}`.
* `GET /jobs/{id}` — return `{"status":"queued|running|succeeded|failed|cancelled","resultUrl":"https://...","detail":"optional"}`. `resultUrl` is required when succeeded.
* `POST /references` — request body is JPEG bytes, returns `{"refUrl":"https://same-gateway/..."}` or `{"id":"safe-id"}`.
* `GET resultUrl` — successful binary video body. Maximum download size is 1 GiB.

The UI asks for explicit consent showing the imported origin and segment count. It runs on one foreground Activity executor, has no background service, and saves atomic app-private per-space checkpoints and stable idempotency IDs. Cancel/destroy prevents another segment submission; reopening with the same job resumes known job IDs. A failure remains a failure and is never shown as completion.

## App integration

Register `SequentialVideoActivity` in the manifest (non-exported) and launch it with `putExtra("spaceId", spaceId)`. Supply `com.doulala.android.video.FfmpegLastFrameExtractor(Context)` from the local FFmpeg package; without it, the Activity stops before submit and reports the missing extractor.


Checkpoints are additionally namespaced by the configured gateway origin, so one sequenceId cannot resume another gateway's provider job.


Polling waits three seconds between real gateway requests, checks cancellation, and has a 20-minute adapter deadline. Provider error bodies/details are not displayed by the UI.

