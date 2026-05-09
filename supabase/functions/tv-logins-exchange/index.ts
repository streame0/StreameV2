import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "authorization, apikey, content-type",
      },
    });
  }

  try {
    const { code, device_nonce } = await req.json();
    if (!code || !device_nonce) {
      return new Response(JSON.stringify({ error: "Missing code or device_nonce" }), { status: 400 });
    }

    // Get the authorization header (the TV's anonymous session token)
    const authHeader = req.headers.get("authorization");
    if (!authHeader) {
      return new Response(JSON.stringify({ error: "Missing authorization" }), { status: 401 });
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
    );

    // Look up the session
    const { data: session, error: sessionError } = await supabase
      .from("tv_login_sessions")
      .select("status, phone_user_id, expires_at")
      .eq("code", code)
      .eq("device_nonce", device_nonce)
      .single();

    if (sessionError || !session) {
      return new Response(JSON.stringify({ error: "Session not found" }), { status: 404 });
    }

    if (session.status !== "approved") {
      return new Response(JSON.stringify({ error: "Session not approved", status: session.status }), { status: 400 });
    }

    if (new Date(session.expires_at) < new Date()) {
      return new Response(JSON.stringify({ error: "Session expired" }), { status: 410 });
    }
 
    if (!session.phone_user_id) {
      return new Response(JSON.stringify({ error: "No phone user linked" }), { status: 400 });
    }

    const tvToken = authHeader.replace("Bearer ", "").trim();
    const { data: tvUserData, error: tvUserError } = await supabase.auth.getUser(tvToken);
    if (tvUserError || !tvUserData?.user?.id) {
      return new Response(JSON.stringify({ error: "Invalid device authorization" }), { status: 401 });
    }

    const tvUserId = tvUserData.user.id;

    // Get the phone user's email so we can generate a magiclink
    const { data: phoneUserData, error: phoneUserError } = await supabase.auth.admin.getUserById(session.phone_user_id);
    if (phoneUserError || !phoneUserData?.user?.email) {
      return new Response(JSON.stringify({ error: "Phone user not found or has no email" }), { status: 400 });
    }
    const phoneEmail = phoneUserData.user.email;

    // Step 1: Generate a magiclink for the phone user
    const { data: linkData, error: linkError } = await supabase.auth.admin.generateLink({
      type: "magiclink",
      email: phoneEmail,
    });

    if (linkError || !linkData?.properties) {
      return new Response(JSON.stringify({ error: "Failed to generate magic link", details: linkError?.message }), { status: 500 });
    }

    // The hashed_token from properties IS the token_hash needed for /auth/v1/verify
    const tokenHash = linkData.properties.hashed_token;
    if (!tokenHash) {
      return new Response(JSON.stringify({ error: "No hashed_token in generated link", debug: JSON.stringify(linkData.properties) }), { status: 500 });
    }

    // Step 3: Verify the magiclink to get actual access/refresh tokens
    const verifyRes = await fetch(`${Deno.env.get("SUPABASE_URL")}/auth/v1/verify`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "apikey": Deno.env.get("SUPABASE_ANON_KEY")!,
      },
      body: JSON.stringify({ token_hash: tokenHash, type: "magiclink" }),
    });

    const verifyData = await verifyRes.json();
    const accessToken = verifyData.access_token || "";
    const refreshToken = verifyData.refresh_token || "";

    if (!accessToken || !refreshToken) {
      return new Response(JSON.stringify({ error: "Failed to verify magic link", details: JSON.stringify(verifyData) }), { status: 500 });
    }

    // Link the TV device user to the phone user as the owner
    const { error: insertError } = await supabase
      .from("linked_devices")
      .upsert(
        {
          owner_id: session.phone_user_id,
          device_user_id: tvUserId,
          device_name: "Streame TV",
          linked_at: new Date().toISOString(),
        },
        { onConflict: "owner_id,device_user_id" }
      );

    if (insertError) {
      console.error("Linked devices upsert error:", insertError);
      // Non-fatal: device linking failed but we still have tokens
    }

    // Mark session as completed
    await supabase
      .from("tv_login_sessions")
      .update({ status: "completed" })
      .eq("code", code);

    return new Response(JSON.stringify({
      access_token: accessToken,
      refresh_token: refreshToken,
      token_type: "bearer",
    }), {
      headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: err.message }), { status: 500 });
  }
});
