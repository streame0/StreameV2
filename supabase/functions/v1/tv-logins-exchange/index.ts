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

    // Exchange: link the device to the phone user
    // The TV was using an anonymous session. We now sign in the TV
    // as the phone user by generating a new session for them.
    const { data: linkData, error: linkError } = await supabase.auth.admin.generateLink({
      type: "magiclink",
      email: "", // We'll use user_id instead
    });

    // Alternative approach: update the TV's anonymous user to link to the phone user
    // by updating the device_user_id in linked_devices
    const { error: insertError } = await supabase
      .from("linked_devices")
      .upsert({
        device_user_id: (await supabase.auth.getUser(authHeader.replace("Bearer ", ""))).data.user?.id,
        phone_user_id: session.phone_user_id,
        device_name: "Streame TV",
        linked_at: new Date().toISOString(),
      }, { onConflict: "device_user_id" });

    if (insertError) {
      return new Response(JSON.stringify({ error: "Failed to link device", details: insertError.message }), { status: 500 });
    }

    // Mark session as completed
    await supabase
      .from("tv_login_sessions")
      .update({ status: "completed" })
      .eq("code", code);

    return new Response(JSON.stringify({ success: true }), {
      headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: err.message }), { status: 500 });
  }
});
