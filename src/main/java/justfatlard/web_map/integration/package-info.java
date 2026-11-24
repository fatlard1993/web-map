/**
 * Links to sibling mods, one class each, compiled against their jars and never bundled. Each names its
 * mod's types, so it is only reached behind that mod's isModLoaded check in {@code Layers}, and each
 * call goes through {@code Layers.guarded}, so a mismatched version turns its layer off instead of
 * stopping the server. They read game state, so they are called on the server thread, except
 * {@link justfatlard.web_map.integration.PhotoLayer}, which reads files.
 */
package justfatlard.web_map.integration;
