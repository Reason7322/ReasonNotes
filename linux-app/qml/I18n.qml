import QtQuick

// All application-chrome text is translated at presentation time.
// User-entered note content and immutable history are never touched.
QtObject {
    id: root
    objectName: "i18n"
    property string language: "en"

    readonly property var polish: ({
        "ReasonNotes": "ReasonNotes",
        "Reason": "Reason",
        "Notes": "Notes",
        "New Note": "Nowa notatka",
        "Save": "Zapisz",
        "Sync": "Synchronizuj",
        "Delete": "Usuń",
        "Search notes…": "Szukaj notatek…",
        "Local · %1": "Lokalnie · %1",
        "Phone · %1": "Telefon · %1",
        "Phone · %1 — %2": "Telefon · %1 — %2",
        "Delete this note?": "Usunąć tę notatkę?",
        "ReasonNotes will create a deletion revision. The note remains recoverable from immutable history.": "ReasonNotes zapisze wersję oznaczającą usunięcie. Notatkę nadal można odzyskać z historii.",
        "Create a new note or select one from the sidebar": "Utwórz notatkę lub wybierz ją z panelu bocznego",
        "A quiet place for your thoughts": "Spokojne miejsce na Twoje myśli",
        "Create a new note or choose one from the sidebar to begin writing in Markdown.": "Utwórz notatkę lub wybierz ją z panelu bocznego, aby zacząć pisać.",
        "Create a note": "Utwórz notatkę",
        "Local: %1": "Lokalnie: %1",
        "Phone: %1": "Telefon: %1",
        "Untitled": "Bez tytułu",
        "Synchronize notes": "Synchronizuj notatki",
        "Choose what this device requests for transfer.": "Wybierz notatki, które mają zostać wysłane z tego urządzenia.",
        "Sync all notes": "Synchronizuj wszystkie",
        "Choose notes to sync": "Wybierz notatki do synchronizacji",
        "Search the synchronization catalogue": "Szukaj na liście notatek",
        "Select All": "Zaznacz wszystkie",
        "Clear Selection": "Wyczyść zaznaczenie",
        "%1 selected": "Wybrano: %1",
        "%1 · %2": "%1 · %2",
        "A transfer request is shown as confirmed only after a ReasonNotes receipt is imported.": "Transfer zostaje potwierdzony dopiero po odebraniu potwierdzenia ReasonNotes.",
        "Cancel": "Anuluj",
        "Start Sync": "Rozpocznij synchronizację",
        "Choose the version to keep": "Wybierz wersję do zachowania",
        "This note changed independently on more than one device. Review each exact revision before deciding.": "Ta notatka została zmieniona niezależnie na kilku urządzeniach. Przejrzyj obie wersje przed podjęciem decyzji.",
        "Linux version": "Wersja z Linuksa",
        "Android version": "Wersja z Androida",
        "Revision %1": "Wersja %1",
        "[Deleted note]": "[Usunięta notatka]",
        "Keep Linux revision": "Zachowaj wersję z Linuksa",
        "Keep Android revision": "Zachowaj wersję z Androida",
        "Superseded revisions remain recoverable from immutable history.": "Poprzednie wersje można odzyskać z niezmiennej historii.",
        "Skip for now": "Pomiń na razie",
        "Settings": "Ustawienia",
        "Back to notes": "Wróć do notatek",
        "Appearance": "Wygląd",
        "Choose the appearance of ReasonNotes.": "Wybierz wygląd aplikacji ReasonNotes.",
        "Dark": "Ciemny",
        "Light": "Jasny",
        "Language": "Język",
        "Choose the language used in menus and controls.": "Wybierz język menu i elementów sterujących.",
        "Settings are saved automatically.": "Ustawienia zapisują się automatycznie.",
        "Your notes stay in their original language.": "Treść Twoich notatek pozostaje bez zmian.",
        "Idle": "Bezczynny",
        "Saved": "Zapisano",
        "Saving": "Zapisywanie",
        "Dirty": "Niezapisane zmiany",
        "Pending": "Oczekuje",
        "Local": "Lokalnie",
        "Confirmed": "Potwierdzono",
        "Completed": "Zakończono",
        "Error": "Błąd",
        "Needs attention": "Wymaga uwagi",
        "Transfer requested": "Zlecono wysyłkę",
        "Deleted note": "Usunięta notatka",
        "Pending deletion": "Oczekuje na usunięcie"
    })

    function t(source) {
        // Reading language here makes all QML text bindings reactive.
        const current = root.language
        if (current !== "pl")
            return source
        const translation = root.polish[source]
        return translation === undefined ? source : translation
    }

    function status(source) {
        return t(source)
    }
}
