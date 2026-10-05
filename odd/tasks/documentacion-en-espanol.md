# Documentación en español y alineada con el código

## Objetivo

Que toda la documentación del repo esté en español y describa el estado real del
código, no el de hace tres sprints.

## Problema

`docs/INTEGRATION.md` está escrito en inglés (249 líneas, 281 marcadores EN contra 0 ES)
mientras el producto, el README y los feature docs están en español. Un repo de producto
español con la referencia técnica en inglés obliga a cada colaborador a traducirla en la
cabeza antes de poder usarla.

Además, la documentación describe un estado que ya no existe:

- `README.md` afirma que "no hay red ni persistencia todavía" y que la capa de datos es
  "TODOs marcados". Hay dos repositorios de Firestore implementados y cableados.
- `README.md` dice "los tres repositorios de Firestore". Los tres archivos existen, pero
  cinco de los seis métodos de `FirestoreDiaryRepository` tiran `NotImplementedError`.
- `README.md` manda a "Decisiones pendientes" del roadmap por cosas ya decididas.
- `ROADMAP.md` deja `FF-4`, `FF-6` y `FF-7` sin marcar, aunque su mayor parte está
  implementada y verificada.

## Por qué ahora

Se acaba de mergear la capa de sesión y la persistencia de perfil/metas. La documentación
es lo que el siguientelector necesita para no volver a deducir lo mismo, y ahora mismo
miente sobre lo que hay en el árbol.

## Alcance

- `docs/INTEGRATION.md`: traducir al español y actualizar el contenido.
- `README.md`: corregir afirmaciones obsoletas y el árbol de archivos.
- `docs/ROADMAP.md`: marcar lo verificado con evidencia y fecha.
- `docs/SPRINT-1.md`: actualizar el estado de la sección de Julian.

## Fuera de alcance

- Escribir `firestore.rules`. Se documenta el hueco, no se cierra.
- Implementar `FirestoreDiaryRepository`.
- Mover el gate de onboarding de `MainActivity` a Firestore (FF-7 pendiente).
- `.atl/skill-registry.md`: es un artefacto generado por tooling, no documentación humana.
  No se traduce a mano.

## Tareas

- [x] T1 — Auditar idioma y obsolescencia de los 12 `.md` del repo.
- [x] T2 — Verificar en el código cada afirmación antes de escribir (nada de checkboxes por
      intuición).
- [x] T3 — Traducir `docs/INTEGRATION.md` al español preservando URLs, código, JSON, nombres
      de clase y cadenas persistidas.
- [x] T4 — Actualizar `docs/INTEGRATION.md`: distinguir layout implementado de layout
      planificado, reconciliar §2.4 con la decisión real de no llamar
      `setFirestoreSettings`, y dejar asentada la ausencia de reglas de seguridad.
- [x] T5 — Actualizar `README.md`: estado real de la capa de datos, árbol de archivos,
      claims de "decisiones pendientes".
- [x] T6 — Actualizar `docs/ROADMAP.md`: `FF-4`, `FF-5`, `FF-6`, `FF-7` con evidencia.
- [x] T7 — Actualizar `docs/SPRINT-1.md`: sección de Julian.
- [x] T8 — Verificar que no quede prosa en inglés fuera de código, identificadores y
      literales de API.

## Decisiones

- **D1.** Las tradacciones preservan verbatim todo lo que no es prosa: URLs, bloques de
  código, JSON de ejemplo, nombres de clase y método, paths, y cadenas que la API o la base
  devuelven (`"product found"`, `"status": 0`). Traducir un literal de respuesta rompe el
  código que lo compara.
- **D2.** La atribución `"Data from Open Food Facts"` se deja en inglés. Es la frase que la
  licencia ODbL pide mostrar, y ya es lo que el usuario final lee en la app.
- **D3.** Los checkboxes se marcan solo contra evidencia verificada en el código, con fecha y
  commit. Un item parcialmente hecho se parte en sub-items, no se marca entero.
- **D4.** La ausencia de `firestore.rules` se documenta como hueco de seguridad bloqueante
  para producción, no como nota al pie. El código ya escribe a Firestore.
- **D5.** No se reescribe el tono de los docs existentes. Se actualiza el contenido obsoleto y
  se traduce; reescribir de más inflate el diff y esconde el cambio real.

## Criterios de aceptación

- Cero prosa en inglés en los `.md` humanos, salvo identificadores, código, literales de API y
  la atribución ODbL.
- Cada afirmación de estado en README y ROADMAP es verificable contra el código.
- El hueco de `firestore.rules` queda explícito.
- Los bloques de código, JSON y URLs sobreviven intactos.

## Verificación

- Grep de marcadores EN/ES sobre los 12 `.md`, con los falsos positivos revisados a mano.
- Conteo de líneas de código antes/después para confirmar que ningún bloque de código cambió.
- Cada item marcado del ROADMAP verificado contra el archivo fuente.

## Ruta

Delegado. Inline por dos razones: el proveedor de subagentes rechazó la llamada
(`free tier can only be used from within OpenCode`), y el trigger de writer pide un solo
writer para 2+ archivos no triviales. Se preserva single-writer: los cuatro archivos se
tocan secuencialmente en esta sesión, nunca en paralelo.

## Estado

Completo. Ver commit de la sesión.