# Plan: Paket-Branches in den Capture-/Rewrite-Refactor übernehmen

Status: **Umgesetzt**

## 1. Ziel

Die Änderungen der drei aufeinander aufbauenden Paket-Branches sollen in den
bestehenden Ziel-Branch `codex/refactor-packet-capture-rewrite` übernommen
werden:

1. `codex/implement-add-entity-packet`
2. `codex/implement-animate-packet`
3. `codex/implement-award-stats-packet`

Der Ziel-Branch soll anschließend sämtliche Implementierungen für
`add_entity`, `animate` und `award_stats` einschließlich ihrer zugehörigen
Tests und technischen Dokumentation enthalten.

## 2. Analysierter Git-Zustand

Die Branches bilden bereits eine lineare Commit-Kette:

```text
codex/refactor-packet-capture-rewrite
└── codex/implement-add-entity-packet
    └── codex/implement-animate-packet
        └── codex/implement-award-stats-packet
```

Die Ancestor-Beziehungen wurden mit `git merge-base --is-ancestor` für jede
Stufe geprüft. Alle Prüfungen waren erfolgreich. Es existieren daher keine
parallelen Änderungen, die zusammengeführt oder manuell aufgelöst werden
müssten.

Zwischen dem aktuellen Ziel-Branch und dem jüngsten Paket-Branch liegen zehn
bereits logisch getrennte Commits:

```text
37ec648 docs: plan add entity packet implementation
974dd5e feat: implement add entity packet definitions
6e4dbda docs: plan add entity data semantics
5337589 fix: handle add entity data semantics explicitly
f8e5f74 docs: remove implementation plans
60c141f docs: plan animate packet implementation
5955634 feat: implement animate packet definitions
33b7bfd docs: remove implementation plans
b34c936 docs: plan award stats packet implementation
1b49e43 feat: implement award stats packet definitions
```

Das unversionierte Verzeichnis `.idea/` gehört nicht zur Aufgabe und bleibt
unangetastet.

## 3. Technische Entscheidung

Der Ziel-Branch wird per Fast-Forward auf den freigegebenen Endstand von
`codex/implement-award-stats-packet` gesetzt. Das erfolgt nach dem Wechsel auf
den Ziel-Branch mit:

```powershell
git merge --ff-only codex/implement-award-stats-packet
```

Ein Fast-Forward ist für diesen Zustand die sicherste und klarste Variante:

- sämtliche vorhandenen Commit-Grenzen und Commit-Nachrichten bleiben
  unverändert erhalten,
- es entsteht kein künstlicher Merge-Commit,
- es werden keine Commits dupliziert,
- es ist kein Cherry-Pick mit möglicher Konflikt- oder Auslassungsgefahr nötig,
- die Quellhistorie bleibt vollständig nachvollziehbar.

Ein Rebase oder anderes History-Rewrite ist weder erforderlich noch geplant.

## 4. Umfang

Übernommen werden ausschließlich die bereits vorhandenen Änderungen der drei
Paket-Branches sowie diese Transferdokumentation. Dadurch gelangen insbesondere
folgende funktionale Änderungen in den Refactor-Branch:

- `add_entity` (`0x01`) für Paper 26.2 und 26.3,
- die explizite Behandlung der typabhängigen `data`-Semantik von
  `add_entity`,
- `animate` (`0x02`) für Paper 26.2 und 26.3,
- `award_stats` (`0x03`) für Paper 26.2 und 26.3,
- die jeweiligen Katalog-, Coverage-, Codec- und Rewriter-Tests.

Nicht Bestandteil sind:

- Änderungen an den Paketimplementierungen selbst,
- neue Refactorings oder Formatierungen,
- eine Änderung der vorhandenen Commit-Historie,
- das Pushen eines Branches zum Remote-Repository,
- das Löschen der drei Quell-Branches.

Die Quell-Branches bleiben nach dem Fast-Forward als lokale Referenzen
erhalten. Ihre Löschung wäre eine separate, potenziell destruktive
Aufräumaktion und wird ohne ausdrücklichen Auftrag nicht vorgenommen.

## 5. Umsetzungsschritte nach Freigabe

1. `origin/master` abrufen und prüfen, ob der lokale `master` weiterhin dem
   Remote-Hauptbranch entspricht.
2. Kontrollieren, dass außer `.idea/` keine uncommitteten oder unversionierten
   Benutzeränderungen vorliegen.
3. Auf `codex/refactor-packet-capture-rewrite` wechseln.
4. `codex/implement-award-stats-packet` ausschließlich mit `--ff-only`
   übernehmen.
5. Die neue Ancestor-Beziehung sowie den Branch-Head kontrollieren.
6. Die betroffenen Adaptertests für `paper-common`, `paper-26_2` und
   `paper-26_3` erneut ausführen.
7. Abschließend `git status`, `git diff --check` und die Commit-Historie
   kontrollieren.

Falls der Fast-Forward wider Erwarten nicht mehr möglich ist, wird keine
alternative Merge- oder Rewrite-Operation automatisch ausgeführt. In diesem
Fall wird der veränderte Zustand erneut analysiert und vor weiteren Änderungen
gemeldet.

## 6. Auswirkungen

Der Vorgang verändert keine Dateiinhalte der bereits implementierten Pakete.
Er verschiebt ausschließlich die lokale Branch-Referenz des Refactor-Branches
auf den gemeinsamen Endstand. Dadurch stehen die drei Paketimplementierungen
künftig direkt auf dem Refactor-Branch für weitere Paketarbeit zur Verfügung.

Da alle drei Feature-Branches linear auf dem Ziel-Branch basieren, ist kein
Verlust bestehender Ziel-Branch-Änderungen möglich. Der vorherige Zielstand
bleibt außerdem als Vorfahr in der Historie erhalten.

## 7. Abnahmekriterien

Die Übernahme ist abgeschlossen, wenn:

- `codex/refactor-packet-capture-rewrite` den vollständigen Endstand aller drei
  Paket-Branches enthält,
- der Vorgang ausschließlich als Fast-Forward erfolgt ist,
- alle ursprünglichen Paket-Commits unverändert in der Historie enthalten
  sind,
- die drei Quell-Branches weiterhin existieren,
- die Adaptertests erfolgreich sind,
- keine zusätzlichen Dateien verändert wurden,
- `.idea/` weiterhin unversioniert und unangetastet ist.
