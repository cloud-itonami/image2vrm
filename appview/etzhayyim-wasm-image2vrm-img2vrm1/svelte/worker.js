/** Minimal API worker for image2vrm. Static SPA assets served by Cloudflare Workers Assets. */
export default {
  async fetch(req, env) {
    const u = new URL(req.url);

    if (u.pathname === "/health" || u.pathname === "/_worker/health")
      return j({ status: "ok", app: "img2vrm1" });

    if (u.pathname === "/_app/meta")
      return j({ nanoid: "img2vrm1", name: "image2vrm", uiMode: "iframe", embedUrl: "https://image2vrm.etzhayyim.com/?embed=1" }, { "Access-Control-Allow-Origin": "*" });

    if (u.pathname.startsWith("/api/r2/")) {
      const key = u.pathname.slice(8);
      const r2 = env.R2;
      if (!r2) return new Response("R2 not bound", { status: 500 });
      const obj = await r2.get(key);
      if (!obj) return new Response("not found", { status: 404 });
      return new Response(obj.body, {
        headers: {
          "Content-Type": obj.httpMetadata?.contentType || "application/octet-stream",
          "Cache-Control": "public, max-age=3600",
          "Access-Control-Allow-Origin": "*",
        },
      });
    }

    // Let Cloudflare Assets handle everything else (SPA static files)
    return env.ASSETS.fetch(req);
  },
};
function j(d, h) { return new Response(JSON.stringify(d), { headers: { "Content-Type": "application/json", ...h } }); }
