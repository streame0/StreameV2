-- ═══════════════════════════════════════════════════════════════
-- StreameV2 — Supabase Cloud Sync Schema
-- Run this SQL in the Supabase SQL Editor to create all tables, 
-- RLS policies, and RPC functions needed for cloud sync.
-- ═══════════════════════════════════════════════════════════════

-- ── Profiles ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS profiles (
    id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_index INT NOT NULL DEFAULT 1,
    name        TEXT NOT NULL DEFAULT '',
    avatar_color_hex TEXT NOT NULL DEFAULT '#1E88E5',
    uses_primary_addons BOOLEAN NOT NULL DEFAULT false,
    uses_primary_plugins BOOLEAN NOT NULL DEFAULT false,
    avatar_id   TEXT,
    avatar_url  TEXT,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, profile_index)
);

DO $$ BEGIN ALTER TABLE profiles ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on profiles'; END $$;
DO $$ BEGIN CREATE POLICY profiles_owner ON profiles FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy profiles_owner already exists'; END $$;

-- ── Addons ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS addons (
    id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    url         TEXT NOT NULL,
    name        TEXT,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    sort_order  INT NOT NULL DEFAULT 0,
    profile_id  INT NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, url, profile_id)
);

DO $$ BEGIN ALTER TABLE addons ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on addons'; END $$;
DO $$ BEGIN CREATE POLICY addons_owner ON addons FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy addons_owner already exists'; END $$;

-- ── Watch Progress ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS watch_progress (
    id           UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id      UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    content_id   TEXT NOT NULL,
    content_type TEXT NOT NULL,
    video_id     TEXT NOT NULL,
    season       INT,
    episode      INT,
    position     BIGINT NOT NULL DEFAULT 0,
    duration     BIGINT NOT NULL DEFAULT 0,
    last_watched BIGINT NOT NULL DEFAULT 0,
    progress_key TEXT NOT NULL,
    profile_id   INT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, progress_key, profile_id)
);

DO $$ BEGIN ALTER TABLE watch_progress ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on watch_progress'; END $$;
DO $$ BEGIN CREATE POLICY watch_progress_owner ON watch_progress FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy watch_progress_owner already exists'; END $$;

-- ── Library (Watchlist) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS library (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id         UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    content_id      TEXT NOT NULL,
    content_type    TEXT NOT NULL,
    name            TEXT NOT NULL DEFAULT '',
    poster          TEXT,
    poster_shape    TEXT NOT NULL DEFAULT 'POSTER',
    background      TEXT,
    description     TEXT,
    release_info    TEXT,
    imdb_rating     FLOAT,
    genres          JSONB DEFAULT '[]'::jsonb,
    addon_base_url  TEXT,
    added_at        BIGINT NOT NULL DEFAULT 0,
    profile_id      INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, content_id, profile_id)
);

DO $$ BEGIN ALTER TABLE library ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on library'; END $$;
DO $$ BEGIN CREATE POLICY library_owner ON library FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy library_owner already exists'; END $$;

-- ── Watched Items ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS watched_items (
    id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id       UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    content_id    TEXT NOT NULL,
    content_type  TEXT NOT NULL,
    title         TEXT NOT NULL DEFAULT '',
    season        INT,
    episode       INT,
    watched_at    BIGINT NOT NULL DEFAULT 0,
    profile_id    INT NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, content_id, season, episode, profile_id)
);

DO $$ BEGIN ALTER TABLE watched_items ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on watched_items'; END $$;
DO $$ BEGIN CREATE POLICY watched_items_owner ON watched_items FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy watched_items_owner already exists'; END $$;

-- ── Profile Settings ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS profile_settings (
    profile_id   INT NOT NULL DEFAULT 1 PRIMARY KEY,
    user_id      UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at   TIMESTAMPTZ DEFAULT now()
);

DO $$ BEGIN ALTER TABLE profile_settings ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on profile_settings'; END $$;
DO $$ BEGIN CREATE POLICY profile_settings_owner ON profile_settings FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy profile_settings_owner already exists'; END $$;

-- ── Collections ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS collections (
    profile_id       INT NOT NULL DEFAULT 1 PRIMARY KEY,
    user_id          UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    collections_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at       TIMESTAMPTZ DEFAULT now()
);

DO $$ BEGIN ALTER TABLE collections ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on collections'; END $$;
DO $$ BEGIN CREATE POLICY collections_owner ON collections FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy collections_owner already exists'; END $$;

-- ── Linked Devices (for sync-code flow) ──────────────────────
CREATE TABLE IF NOT EXISTS linked_devices (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id        UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    device_user_id  UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    device_name     TEXT,
    linked_at       TIMESTAMPTZ DEFAULT now(),
    UNIQUE(owner_id, device_user_id)
);

DO $$ BEGIN ALTER TABLE linked_devices ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on linked_devices'; END $$;
DO $$ BEGIN CREATE POLICY linked_devices_owner ON linked_devices FOR ALL USING (auth.uid() = owner_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy linked_devices_owner already exists'; END $$;

-- ── Sync Codes ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_codes (
    id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id   UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    code       TEXT NOT NULL UNIQUE,
    pin_hash   TEXT NOT NULL,
    claimed    BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

DO $$ BEGIN ALTER TABLE sync_codes ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on sync_codes'; END $$;
DO $$ BEGIN CREATE POLICY sync_codes_owner ON sync_codes FOR ALL USING (auth.uid() = owner_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy sync_codes_owner already exists'; END $$;

-- ── TV Login Sessions ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tv_login_sessions (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    code            TEXT NOT NULL UNIQUE,
    device_nonce    TEXT NOT NULL,
    device_user_id  UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    device_name     TEXT,
    phone_user_id   UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMPTZ DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

DO $$ BEGIN ALTER TABLE tv_login_sessions ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on tv_login_sessions'; END $$;
DO $$ BEGIN CREATE POLICY tv_login_sessions_insert ON tv_login_sessions FOR INSERT WITH CHECK (true); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy tv_login_sessions_insert already exists'; END $$;
DO $$ BEGIN CREATE POLICY tv_login_sessions_select ON tv_login_sessions FOR SELECT USING (auth.uid() = device_user_id OR auth.uid() = phone_user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy tv_login_sessions_select already exists'; END $$;
DO $$ BEGIN CREATE POLICY tv_login_sessions_update ON tv_login_sessions FOR UPDATE USING (auth.uid() = device_user_id OR auth.uid() = phone_user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy tv_login_sessions_update already exists'; END $$;

-- ═══════════════════════════════════════════════════════════════
-- RPC Functions
-- ═══════════════════════════════════════════════════════════════

-- get_sync_owner: returns the effective owner user ID for linked devices
CREATE OR REPLACE FUNCTION get_sync_owner()
RETURNS UUID
LANGUAGE sql SECURITY DEFINER STABLE
AS $$
    SELECT COALESCE(
        (SELECT owner_id FROM linked_devices WHERE device_user_id = auth.uid() LIMIT 1),
        auth.uid()
    );
$$;

-- generate_sync_code
CREATE OR REPLACE FUNCTION generate_sync_code(p_pin TEXT)
RETURNS TABLE(code TEXT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_code TEXT;
    v_pin_hash TEXT;
    v_owner UUID;
BEGIN
    v_owner := auth.uid();
    v_code := upper(substring(md5(random()::text) from 1 for 6));
    v_pin_hash := crypt(p_pin, gen_salt('bf'));
    
    INSERT INTO sync_codes (owner_id, code, pin_hash, expires_at)
    VALUES (v_owner, v_code, v_pin_hash, now() + interval '10 minutes')
    RETURNING sync_codes.code INTO v_code;
    
    RETURN QUERY SELECT v_code;
END;
$$;

-- get_sync_code
CREATE OR REPLACE FUNCTION get_sync_code(p_pin TEXT)
RETURNS TABLE(code TEXT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_pin_hash TEXT;
BEGIN
    SELECT pin_hash INTO v_pin_hash FROM sync_codes
    WHERE owner_id = auth.uid() AND claimed = false AND expires_at > now()
    ORDER BY created_at DESC LIMIT 1;
    
    IF v_pin_hash IS NULL THEN RETURN; END IF;
    IF NOT crypt(p_pin, v_pin_hash) = v_pin_hash THEN RETURN; END IF;
    
    RETURN QUERY SELECT code FROM sync_codes
    WHERE owner_id = auth.uid() AND claimed = false AND expires_at > now()
    ORDER BY created_at DESC LIMIT 1;
END;
$$;

-- claim_sync_code
CREATE OR REPLACE FUNCTION claim_sync_code(
    p_code TEXT,
    p_pin TEXT,
    p_device_name TEXT DEFAULT NULL
)
RETURNS TABLE(result_owner_id UUID, success BOOLEAN, message TEXT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_sync_code RECORD;
BEGIN
    SELECT * INTO v_sync_code FROM sync_codes
    WHERE code = p_code AND claimed = false AND expires_at > now()
    LIMIT 1;
    
    IF NOT FOUND THEN
        RETURN QUERY SELECT NULL::UUID, false, 'Invalid or expired code'::TEXT;
        RETURN;
    END IF;
    
    IF NOT crypt(p_pin, v_sync_code.pin_hash) = v_sync_code.pin_hash THEN
        RETURN QUERY SELECT NULL::UUID, false, 'Invalid PIN'::TEXT;
        RETURN;
    END IF;
    
    UPDATE sync_codes SET claimed = true WHERE id = v_sync_code.id;
    
    INSERT INTO linked_devices (owner_id, device_user_id, device_name)
    VALUES (v_sync_code.owner_id, auth.uid(), p_device_name)
    ON CONFLICT (owner_id, device_user_id) DO UPDATE SET device_name = COALESCE(p_device_name, linked_devices.device_name);
    
    RETURN QUERY SELECT v_sync_code.owner_id, true, 'Device linked successfully'::TEXT;
END;
$$;

-- unlink_device
CREATE OR REPLACE FUNCTION unlink_device(p_device_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM linked_devices WHERE owner_id = auth.uid() AND device_user_id = p_device_user_id;
END;
$$;

-- sync_push_addons
CREATE OR REPLACE FUNCTION sync_push_addons(p_addons JSONB, p_profile_id INT DEFAULT 1)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM addons WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    INSERT INTO addons (user_id, url, name, sort_order, profile_id)
    SELECT auth.uid(), a->>'url', a->>'name', (a->>'sort_order')::int, p_profile_id
    FROM jsonb_array_elements(p_addons) AS a;
END;
$$;

-- sync_push_watch_progress
CREATE OR REPLACE FUNCTION sync_push_watch_progress(p_items JSONB, p_profile_id INT DEFAULT 1)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM watch_progress WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    INSERT INTO watch_progress (user_id, content_id, content_type, video_id, season, episode, position, duration, last_watched, progress_key, profile_id)
    SELECT auth.uid(), i->>'content_id', i->>'content_type', i->>'video_id',
           (i->>'season')::int, (i->>'episode')::int,
           (i->>'position')::bigint, (i->>'duration')::bigint,
           (i->>'last_watched')::bigint, i->>'progress_key', p_profile_id
    FROM jsonb_array_elements(p_items) AS i;
END;
$$;

-- sync_push_library
CREATE OR REPLACE FUNCTION sync_push_library(p_items JSONB, p_profile_id INT DEFAULT 1)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM library WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    INSERT INTO library (user_id, content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating, addon_base_url, added_at, profile_id)
    SELECT auth.uid(), i->>'content_id', i->>'content_type', i->>'name',
           i->>'poster', COALESCE(i->>'poster_shape', 'POSTER'),
           i->>'background', i->>'description', i->>'release_info',
           (i->>'imdb_rating')::float, i->>'addon_base_url',
           (i->>'added_at')::bigint, p_profile_id
    FROM jsonb_array_elements(p_items) AS i;
END;
$$;

-- sync_push_watched_items
CREATE OR REPLACE FUNCTION sync_push_watched_items(p_items JSONB, p_profile_id INT DEFAULT 1)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM watched_items WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    INSERT INTO watched_items (user_id, content_id, content_type, title, season, episode, watched_at, profile_id)
    SELECT auth.uid(), i->>'content_id', i->>'content_type', i->>'title',
           (i->>'season')::int, (i->>'episode')::int,
           (i->>'watched_at')::bigint, p_profile_id
    FROM jsonb_array_elements(p_items) AS i;
END;
$$;

-- sync_push_profile_settings
CREATE OR REPLACE FUNCTION sync_push_profile_settings(p_profile_id INT, p_settings_json JSONB)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    INSERT INTO profile_settings (profile_id, user_id, settings_json, updated_at)
    VALUES (p_profile_id, auth.uid(), p_settings_json, now())
    ON CONFLICT (profile_id) DO UPDATE
    SET settings_json = p_settings_json, updated_at = now();
END;
$$;

-- sync_push_collections
CREATE OR REPLACE FUNCTION sync_push_collections(p_profile_id INT, p_collections_json JSONB)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    INSERT INTO collections (profile_id, user_id, collections_json, updated_at)
    VALUES (p_profile_id, auth.uid(), p_collections_json, now())
    ON CONFLICT (profile_id) DO UPDATE
    SET collections_json = p_collections_json, updated_at = now();
END;
$$;

-- sync_delete_watch_progress
CREATE OR REPLACE FUNCTION sync_delete_watch_progress(p_progress_key TEXT, p_profile_id INT DEFAULT 1)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM watch_progress WHERE user_id = auth.uid() AND progress_key = p_progress_key AND profile_id = p_profile_id;
END;
$$;

-- start_tv_login_session
CREATE OR REPLACE FUNCTION start_tv_login_session(
    p_device_nonce TEXT,
    p_redirect_base_url TEXT DEFAULT '',
    p_device_name TEXT DEFAULT NULL
)
RETURNS TABLE(code TEXT, web_url TEXT, expires_at TEXT, poll_interval_seconds INT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_code TEXT;
    v_expires TIMESTAMPTZ;
BEGIN
    v_code := upper(substring(md5(random()::text) from 1 for 6));
    v_expires := now() + interval '5 minutes';
    
    INSERT INTO tv_login_sessions (code, device_nonce, device_user_id, device_name, status, expires_at)
    VALUES (v_code, p_device_nonce, auth.uid(), p_device_name, 'pending', v_expires);
    
    RETURN QUERY SELECT 
        v_code,
        p_redirect_base_url || '?code=' || v_code,
        to_char(v_expires, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        3;
END;
$$;

-- poll_tv_login_session
CREATE OR REPLACE FUNCTION poll_tv_login_session(p_code TEXT, p_device_nonce TEXT)
RETURNS TABLE(status TEXT, expires_at TEXT, poll_interval_seconds INT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_session RECORD;
BEGIN
    SELECT * INTO v_session FROM tv_login_sessions
    WHERE code = p_code AND device_nonce = p_device_nonce
    LIMIT 1;
    
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'not_found'::TEXT, NULL::TEXT, NULL::INT;
        RETURN;
    END IF;
    
    IF v_session.expires_at < now() THEN
        RETURN QUERY SELECT 'expired'::TEXT, NULL::TEXT, NULL::INT;
        RETURN;
    END IF;
    
    RETURN QUERY SELECT v_session.status, to_char(v_session.expires_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), 3;
END;
$$;

-- ═══════════════════════════════════════════════════════════════
-- Phase 1: Additional RLS policies
-- ═══════════════════════════════════════════════════════════════

-- Allow linked device users to see their own link
DO $$ BEGIN CREATE POLICY linked_devices_device ON linked_devices FOR SELECT USING (auth.uid() = device_user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy linked_devices_device already exists'; END $$;

-- ═══════════════════════════════════════════════════════════════
-- Phase 4: Profile PIN locks
-- ═══════════════════════════════════════════════════════════════

-- Add pin_hash column to profiles (nullable = PIN not set)
DO $$ BEGIN ALTER TABLE profiles ADD COLUMN pin_hash TEXT; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'pin_hash column already exists on profiles'; END $$;
DO $$ BEGIN ALTER TABLE profiles ADD COLUMN pin_enabled BOOLEAN NOT NULL DEFAULT false; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'pin_enabled column already exists on profiles'; END $$;

-- verify_profile_pin
CREATE OR REPLACE FUNCTION verify_profile_pin(p_profile_id INT, p_pin TEXT)
RETURNS TABLE(unlocked BOOLEAN, message TEXT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_pin_hash TEXT;
BEGIN
    SELECT pin_hash INTO v_pin_hash FROM profiles
    WHERE user_id = auth.uid() AND profile_index = p_profile_id;

    IF v_pin_hash IS NULL THEN
        RETURN QUERY SELECT true, 'No PIN set'::TEXT;
        RETURN;
    END IF;

    IF crypt(p_pin, v_pin_hash) = v_pin_hash THEN
        RETURN QUERY SELECT true, 'PIN verified'::TEXT;
    ELSE
        RETURN QUERY SELECT false, 'Incorrect PIN'::TEXT;
    END IF;
END;
$$;

-- set_profile_pin
CREATE OR REPLACE FUNCTION set_profile_pin(p_profile_id INT, p_pin TEXT, p_current_pin TEXT DEFAULT NULL)
RETURNS TABLE(unlocked BOOLEAN, message TEXT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_existing_hash TEXT;
BEGIN
    SELECT pin_hash INTO v_existing_hash FROM profiles
    WHERE user_id = auth.uid() AND profile_index = p_profile_id;

    IF v_existing_hash IS NOT NULL AND p_current_pin IS NOT NULL THEN
        IF crypt(p_current_pin, v_existing_hash) != v_existing_hash THEN
            RETURN QUERY SELECT false, 'Current PIN is incorrect'::TEXT;
            RETURN;
        END IF;
    END IF;

    UPDATE profiles SET pin_hash = crypt(p_pin, gen_salt('bf')), pin_enabled = true, updated_at = now()
    WHERE user_id = auth.uid() AND profile_index = p_profile_id;

    RETURN QUERY SELECT true, 'PIN set'::TEXT;
END;
$$;

-- clear_profile_pin
CREATE OR REPLACE FUNCTION clear_profile_pin(p_profile_id INT, p_current_pin TEXT DEFAULT NULL)
RETURNS TABLE(unlocked BOOLEAN, message TEXT)
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_existing_hash TEXT;
BEGIN
    SELECT pin_hash INTO v_existing_hash FROM profiles
    WHERE user_id = auth.uid() AND profile_index = p_profile_id;

    IF v_existing_hash IS NOT NULL AND p_current_pin IS NOT NULL THEN
        IF crypt(p_current_pin, v_existing_hash) != v_existing_hash THEN
            RETURN QUERY SELECT false, 'Current PIN is incorrect'::TEXT;
            RETURN;
        END IF;
    END IF;

    UPDATE profiles SET pin_hash = NULL, pin_enabled = false, updated_at = now()
    WHERE user_id = auth.uid() AND profile_index = p_profile_id;

    RETURN QUERY SELECT true, 'PIN cleared'::TEXT;
END;
$$;

-- ═══════════════════════════════════════════════════════════════
-- Phase 4: Avatar catalog
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS avatars (
    id           UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    display_name TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    category     TEXT NOT NULL DEFAULT 'general',
    sort_order   INT NOT NULL DEFAULT 0,
    bg_color     TEXT
);

-- Public read access for avatar catalog
DO $$ BEGIN ALTER TABLE avatars ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on avatars'; END $$;
DO $$ BEGIN CREATE POLICY avatars_public_read ON avatars FOR SELECT USING (true); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy avatars_public_read already exists'; END $$;

-- get_avatar_catalog
CREATE OR REPLACE FUNCTION get_avatar_catalog()
RETURNS TABLE(id UUID, display_name TEXT, storage_path TEXT, category TEXT, sort_order INT, bg_color TEXT)
LANGUAGE sql SECURITY DEFINER STABLE
AS $$
    SELECT id, display_name, storage_path, category, sort_order, bg_color FROM avatars ORDER BY sort_order;
$$;

-- ═══════════════════════════════════════════════════════════════
-- Phase 3: Home catalog settings sync
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS home_catalog_settings (
    profile_id       INT NOT NULL DEFAULT 1 PRIMARY KEY,
    user_id          UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    settings_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at       TIMESTAMPTZ DEFAULT now()
);

DO $$ BEGIN ALTER TABLE home_catalog_settings ENABLE ROW LEVEL SECURITY; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'RLS already enabled on home_catalog_settings'; END $$;
DO $$ BEGIN CREATE POLICY home_catalog_settings_owner ON home_catalog_settings FOR ALL USING (auth.uid() = user_id); EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'Policy home_catalog_settings_owner already exists'; END $$;

-- sync_push_home_catalog_settings
CREATE OR REPLACE FUNCTION sync_push_home_catalog_settings(p_profile_id INT, p_settings_json JSONB)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
BEGIN
    INSERT INTO home_catalog_settings (profile_id, user_id, settings_json, updated_at)
    VALUES (p_profile_id, auth.uid(), p_settings_json, now())
    ON CONFLICT (profile_id) DO UPDATE
    SET settings_json = p_settings_json, updated_at = now();
END;
$$;

-- ═══════════════════════════════════════════════════════════════
-- Phase 5: Performance indexes
-- ═══════════════════════════════════════════════════════════════

CREATE INDEX IF NOT EXISTS idx_tv_login_sessions_code ON tv_login_sessions(code);
CREATE INDEX IF NOT EXISTS idx_tv_login_sessions_device_user ON tv_login_sessions(device_user_id);
CREATE INDEX IF NOT EXISTS idx_linked_devices_owner ON linked_devices(owner_id);
CREATE INDEX IF NOT EXISTS idx_linked_devices_device ON linked_devices(device_user_id);
CREATE INDEX IF NOT EXISTS idx_watch_progress_user_profile ON watch_progress(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_library_user_profile ON library(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_watched_items_user_profile ON watched_items(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_addons_user_profile ON addons(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_sync_codes_owner ON sync_codes(owner_id);
CREATE INDEX IF NOT EXISTS idx_sync_codes_code ON sync_codes(code);

-- ═══════════════════════════════════════════════════════════════
-- Phase 5: Soft-delete support for account deletion
-- ═══════════════════════════════════════════════════════════════

-- Add deleted_at column to user-owned tables for soft-delete before hard delete
DO $$ BEGIN ALTER TABLE profiles ADD COLUMN deleted_at TIMESTAMPTZ; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'deleted_at already exists on profiles'; END $$;
DO $$ BEGIN ALTER TABLE addons ADD COLUMN deleted_at TIMESTAMPTZ; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'deleted_at already exists on addons'; END $$;
DO $$ BEGIN ALTER TABLE library ADD COLUMN deleted_at TIMESTAMPTZ; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'deleted_at already exists on library'; END $$;
DO $$ BEGIN ALTER TABLE watched_items ADD COLUMN deleted_at TIMESTAMPTZ; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'deleted_at already exists on watched_items'; END $$;
DO $$ BEGIN ALTER TABLE watch_progress ADD COLUMN deleted_at TIMESTAMPTZ; EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'deleted_at already exists on watch_progress'; END $$;

-- ═══════════════════════════════════════════════════════════════
-- Phase 2: Delete account helper RPC (soft-delete + mark)
-- ═══════════════════════════════════════════════════════════════

-- soft_delete_user_data: marks all user data as deleted before Edge Function hard-deletes the auth user
CREATE OR REPLACE FUNCTION soft_delete_user_data()
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER
AS $$
DECLARE
    v_uid UUID;
BEGIN
    v_uid := auth.uid();
    UPDATE profiles SET deleted_at = now() WHERE user_id = v_uid;
    UPDATE addons SET deleted_at = now() WHERE user_id = v_uid;
    UPDATE library SET deleted_at = now() WHERE user_id = v_uid;
    UPDATE watched_items SET deleted_at = now() WHERE user_id = v_uid;
    UPDATE watch_progress SET deleted_at = now() WHERE user_id = v_uid;
    DELETE FROM profile_settings WHERE user_id = v_uid;
    DELETE FROM collections WHERE user_id = v_uid;
    DELETE FROM home_catalog_settings WHERE user_id = v_uid;
    DELETE FROM sync_codes WHERE owner_id = v_uid;
    DELETE FROM linked_devices WHERE owner_id = v_uid OR device_user_id = v_uid;
    DELETE FROM tv_login_sessions WHERE device_user_id = v_uid OR phone_user_id = v_uid;
END;
$$;
