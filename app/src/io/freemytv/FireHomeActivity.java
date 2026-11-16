package io.freemytv;

/**
 * The home screen on Fire TV (where Amazon keeps the Home button). Unlike {@link HomeActivity}, its manifest entry
 * omits {@code resumeWhilePausing}: on Fire OS that attribute on a non-HOME activity leaves window focus on the
 * pausing launcher and drops remote key events. {@link RemoteKeyService} brings it back to the front on every Home
 * press; a repeated start (singleTask) comes back through {@link HomeActivity#onNewIntent}, which returns the screen to
 * its entry state.
 */
public class FireHomeActivity extends HomeActivity {}
