# Règles R8 / ProGuard pour Toque (Connect Android 6.0 & Smart Android 8.1 vmSafeMode).
# Aucune bibliothèque tierce, aucune réflexion : seules les énumérations sérialisées par nom
# (IPC Bundle, JSON Compagnon, SQLite) et les numéros de ligne pour Logbook sont conservés.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Énumérations sérialisées par .name / .valueOf() entre processus (:machine), JSON et SQLite.
-keep enum app.toque.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
