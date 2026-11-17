# Plan de cambios: CRM y diseño de la app

Lista acordada en la sesión "CRM actualizaciones lista de cambios" más el cambio 13 de diseño.
Cada cambio se implementa, se prueba y se revisa antes del siguiente de su carril.
Se trabaja en `develop`; nadie hace commit ni push (el usuario lo hace desde IntelliJ).

## Cambios del CRM

1. **Automatización de salida editable**
   - **Disparador:** la fecha de check-out del lead, a una hora que cada empresa configura (por defecto 2:00 p. m., en su zona horaria).
   - **Etapas de origen:** se eligen una o varias (por ejemplo Booked o Converted). Solo se archivan los leads que estén en ellas.
   - **Acción:** archivar el lead.
   - **Sin Cloudbeds:** el lead se archiva a la hora configurada.
   - **Con Cloudbeds:** el lead solo se archiva cuando Cloudbeds marca la reserva como checked-out.
   - **Configuración:** cada empresa la activa o desactiva y la edita en los ajustes del CRM (pestaña Automatizaciones del cambio 12).
2. **Ver archivados**
   - **Checkbox:** la vista de leads tiene un checkbox "Mostrar archivados".
   - **En pipeline:** al activarlo, aparece una columna "Archivados" después de la última etapa.
   - **En lista:** al activarlo, se incluyen los archivados marcados como tales.
   - **Recordar:** el checkbox recuerda su estado para cada usuario.
   - **Restaurar:** un lead archivado se puede restaurar desde su ficha y desde la columna "Archivados". Vuelve a la etapa en la que estaba y queda registrado en su historial. Un lead archivado no se puede mover de etapa hasta restaurarlo.
3. **Sección "Sin calificar"**
   - **Título:** la sección "Recepción" pasa a llamarse "Sin calificar". Solo cambia el título: "Recepción" se queda en el permiso, la etiqueta "Sin interés (recepción)" y los mensajes, porque es un rol.
   - **Qué muestra:** los leads que no tienen interés o no tienen usuario asignado. Hoy solo muestra los que no tienen interés.
   - **Cuándo sale un lead:** en cuanto tiene un interés o un usuario asignado.
4. **Quitar la sección "Asignaciones"**
   - **Dónde queda la sugerencia:** el responsable que sugiere el origen del lead pasa a "Sin calificar". El lead muestra "Sugerido: Fulano" con botones para aprobar o descartar.
   - **Agentes IA:** `crm_list_pending_assignments` y `crm_resolve_assignment` se mantienen.
5. **Menú reordenado**
   - **Nuevo lead:** pasa al primer lugar del menú.
   - **Pipeline y Leads:** se unen en un solo item "Leads".
   - El orden final del menú lo fija el cambio 12.
6. **Vista unificada de leads**
   - **Selector:** dentro de "Leads" hay un selector Pipeline / Lista.
   - **Filtros compartidos:** las dos vistas usan los mismos filtros (interés, Mis leads, búsqueda).
   - **Recordar:** el selector recuerda la última vista de cada usuario.
   - **Enlaces anteriores:** los enlaces a la sección "pipeline" siguen funcionando y abren la vista Pipeline.
7. **Scroll horizontal cuando la vista no cabe**
   - **Dónde pasa:** en la web y en la app de escritorio, en ciertas resoluciones. Las columnas finales del pipeline salen cortadas, como Cold.
   - **El arreglo:** el pipeline muestra una barra de scroll horizontal visible y las columnas mantienen un ancho mínimo en vez de comprimirse.
   - **Otras vistas:** las tablas y listas anchas también tendrán scroll horizontal.
   - **Alcance:** se arregla en el componente kanban compartido, así que también sirve para otros módulos que usen pipeline.
8. **Menciones en notas**
   - **Cómo se etiqueta:** al escribir `@` en una nota del lead sale un selector de usuarios de la empresa.
   - **A quién se puede etiquetar:** a cualquier usuario con permiso para ver el CRM, aunque el lead no esté asignado a él ni sea de sus intereses.
   - **Acceso que da la mención:**
     - **Dónde lo ve:** el lead aparece en su vista "Leads", en pipeline y en lista, con una marca "Mencionado".
     - **Qué puede hacer:** abrirlo, leer la conversación y las notas, y responder con notas. Nada más.
     - **Hasta cuándo:** mientras el lead esté abierto. Si se archiva, deja de verlo, salvo que active "Mostrar archivados".
     - **Quitarle el acceso:** quien gestiona el lead puede quitárselo desde el lead.
   - **En la nota:** la mención se ve resaltada.
   - **Aviso:** notificación en el centro de notificaciones (cambio 10) y, si lo tiene activado, un correo al correo de su perfil.
   - **Preferencia:** en "Equipo" se añade la opción "Avisarme por correo cuando me mencionen".
   - **Agentes IA:** `crm_add_lead_note` puede mencionar usuarios, y el agente del usuario mencionado también ve ese lead.
11. **Varias personas asignadas a un lead**
    - **En el lead:** el campo "Asignado" pasa a ser una lista de usuarios. Todos son iguales, sin responsable principal.
    - **Visibilidad:** cada asignado ve el lead aunque no sea de sus intereses.
    - **Mis leads:** muestra los leads donde el usuario es uno de los asignados.
    - **Sin calificar (cambio 3):** un lead sale de ahí si tiene al menos un asignado o un interés.
    - **Sugerencias (cambio 4):** aprobar una sugerencia añade a esa persona a los asignados.
    - **Avisos:** cada usuario que se añade recibe "Te asignaron un lead" en el centro de notificaciones y, si lo tiene activado, por correo.
    - **Acciones en lote:** se puede añadir o quitar un asignado en varios leads a la vez.
    - **Pipeline y lista:** las tarjetas y filas muestran las iniciales o avatares de los asignados.
    - **Seguimientos y resumen diario:** le llegan a todos los asignados.
    - **Plantillas:** la variable del asignado muestra todos los nombres separados por coma.
    - **Reportes por usuario:** cada lead cuenta para cada uno de sus asignados.
    - **Datos actuales:** el asignado de hoy de cada lead pasa a la lista sin perderse.
    - **Importación de Monday:** si la columna de personas tiene varias, se importan todas. Cada persona se empareja con un usuario por correo o por la tabla de equivalencias; las que no tienen usuario se omiten y se anotan en el informe, sin crear usuarios. Reimportar añade los nuevos sin duplicar.
    - **CSV:** la columna de asignados acepta varios nombres o correos separados por coma o punto y coma.
    - **Otras importaciones:** WordPress, Encuentra24 y Cloudbeds siguen funcionando y añaden el asignado a la lista.
    - **Agentes IA:** `crm_update_lead`, `crm_create_lead` y `crm_bulk_update_leads` aceptan varios asignados, con opciones para añadir y quitar.
12. **Configuración agrupada**
    - **Menú principal:** Nuevo lead · Leads · Sin calificar · Seguimientos · Configuración.
    - **Pestañas de "Configuración":**

      | Pestaña | Qué contiene |
      | --- | --- |
      | General | Etapas, intereses, hora del resumen diario |
      | Automatizaciones | La regla de salida del cambio 1 y las que vengan después |
      | Equipo | Usuarios, correos y preferencias de aviso |
      | Correo | SMTP e IMAP |
      | Plantillas | Plantillas de mensajes |
      | Anti-spam | Reglas anti-spam |
      | Integraciones | Meta, WordPress, Encuentra24, Cloudbeds |
      | Importar | Monday, CSV, LeadsPro, URL e historial de importaciones |

    - **Acciones en lote:** dejan de ser una sección. Pasan a la vista Lista de "Leads" con checkbox por lead y una barra de acciones (mover, asignar, archivar y otras).
    - **Permisos:** "Configuración" solo con `crm.settings`; la barra de acciones en lote solo con `crm.manage`.
    - **Enlaces anteriores:** las direcciones viejas, como `mail` o `spam`, siguen funcionando y abren su pestaña.

## Otros módulos y Core

9. **Códigos copiables**
   - **Código de cliente (módulo Clientes):** se puede copiar en la ficha del cliente, en la lista de clientes y donde el CRM lo muestre en la ficha del lead.
   - **Código de usuario (Usuarios de la empresa):** se puede copiar en la ficha del usuario y en la lista de usuarios.
   - **Cómo:** con el mismo mecanismo que ya usa "Mi acceso WiFi".
   - **Dónde se hace:** en `app/shared`, no en los módulos. "Mi acceso WiFi" solo hace el texto seleccionable (`SelectionContainer` en `ModuleMetric` de `NativeModuleRenderer.kt`). La ficha y la lista de clientes (`ServerDrivenModuleScreen.kt`) y las de usuarios (`CompanyUsersSection.kt`) son pantallas nativas de la app. Por eso va en el carril UI, después del 13.
   - **Ficha del lead:** el código de cliente que muestra el CRM se hace junto con el resto del carril CRM.
10. **Centro de notificaciones (Core, para todos los módulos)**
    - **Campana:** en la barra superior de la app (web, escritorio y móvil) con un contador de no leídas.
    - **El panel:** cada notificación con título, texto, módulo de origen y fecha. Al hacer clic abre la pantalla correspondiente y queda como leída.
    - **Acciones:** marcar todo como leído; las notificaciones de más de 90 días se limpian solas.
    - **Para los módulos:** el Core ofrece un servicio para que cualquier módulo cree notificaciones para un usuario (CRM: menciones, leads asignados, seguimientos vencidos).
    - **Privacidad:** cada usuario solo ve las suyas, dentro de su empresa.
    - **Agentes IA:** herramientas MCP para listar las notificaciones del usuario y marcarlas como leídas.
    - **Fase 1:** se actualizan al abrir el panel y cada minuto. Las push quedan para después.
13. **Diseño de escritorio y contraste**
    - **El problema:** todas las pantallas usan `fillMaxWidth(0.9f)` sin ancho máximo (login, dashboard, ajustes, módulos), así que en escritorio y web los campos ocupan toda la pantalla y la app parece una app móvil estirada.
    - **Login:**
      - El formulario va en una tarjeta centrada con ancho máximo (unos 420 dp) sobre el fondo de marca.
      - Dentro de la tarjeta los campos usan colores de superficie del tema, no blanco translúcido sobre el degradado.
      - En móvil se mantiene a lo ancho, con márgenes.
    - **Contraste:**
      - Hoy, en el tema Claro, los campos del login son `onAccent` al 30 % (y otra capa al 50 %) sobre el degradado, con texto `onAccent`. Con colores de marca claros el texto casi no se lee.
      - Todo texto y etiqueta debe cumplir WCAG AA: 4.5:1 para texto normal y 3:1 para bordes de campos y texto grande.
      - Si un color de marca no da contraste suficiente, el tema elige automáticamente texto oscuro o claro.
    - **Aspecto de escritorio:**
      - Las pantallas de contenido tienen un ancho máximo legible y, en pantallas anchas, aprovechan columnas o la barra lateral en vez de estirar formularios.
      - En escritorio, menos relleno y tamaños más compactos que en móvil.
      - El emoji del ojo de la contraseña se reemplaza por un icono.
    - **Alcance:** el login es solo un ejemplo. Se revisan **todas las vistas** de la app: login, recuperación de contraseña, dashboard y barra lateral, ajustes, perfil, empresas y usuarios, diálogos, y todas las pantallas de módulos que pinta el renderizador (`ServerDrivenModuleScreen`, `NativeModuleRenderer`: formularios, tablas, tarjetas, kanban y acordeones). Se cambia el tema (`AppTheme.kt`) y la presentación, sin tocar la lógica.
    - **Inventario:** antes de cambiar nada se hace una lista de cada pantalla con sus problemas de ancho, densidad y contraste, y se arreglan todos.
    - **Verificación:** capturas antes y después en escritorio (1366 y 1920 de ancho) y en móvil, en tema Claro y Oscuro.

## Equipo de subagentes

El supervisor es la sesión principal (Opus): reparte las tareas, revisa cada diff y decide cuándo pasar al siguiente cambio.
Los modelos siguen la regla de `ecc:model-route`: Haiku para lo mecánico, Sonnet para implementar y revisar, y Opus solo para el supervisor.

| Rol | Subagente | Modelo | Qué hace |
| --- | --- | --- | --- |
| Explorador | `idc-explorador` | Haiku | Ubica archivos, funciones y pruebas para un cambio y devuelve un mapa corto. Solo lee. |
| Implementador | `idc-implementador` | Sonnet | Implementa un cambio del CRM, Core u otros módulos con sus pruebas, migraciones, README y versión. |
| Diseñador UI | `idc-disenador-ui` | Sonnet | Cambios 7 y 13, con las skills `material-3`, `mobile-android-design`, `compose-component-design` y `ecc:accessibility`. |
| Tester | `idc-tester` | Haiku | Compila y corre las pruebas; devuelve solo los fallos. |
| Revisor Kotlin | `ecc:kotlin-reviewer` | Sonnet | Revisa cada diff antes de cerrarlo. |
| Revisor de base de datos | `ecc:database-reviewer` | Sonnet | Revisa migraciones y consultas (cambios 1, 8, 10 y 11). |
| Arreglo de compilación | `ecc:kotlin-build-resolver` | Sonnet | Solo si la compilación falla y el implementador no lo resuelve. |

## Orden de ejecución

Se trabaja por carriles. Dos carriles nunca tocan los mismos archivos, así que pueden correr a la vez.
El cierre de cada ola es: tester, revisores y luego el visto bueno del supervisor.

| Ola | Carril UI (`app/shared`) | Carril CRM (`modules/crm`) | Carril Core y otros módulos |
| --- | --- | --- | --- |
| 1 | 13 Diseño y contraste | 3 Sin calificar → 4 Quitar Asignaciones → 1 Automatización → 2 Ver archivados | — |
| 2 | 9 Códigos copiables → 7 Scroll horizontal (kanban y tablas compartidas) | 5 + 6 Menú y vista unificada → 12 Configuración agrupada | 10 Centro de notificaciones (Core, servidor y campana) |
| 3 | — | 11 Varias personas asignadas → 8 Menciones en notas | — |

- **Por qué este orden:** el 8 necesita el 10 (avisos) y el 11 (visibilidad). El 12 cambia el mismo menú que el 5 y el 6. El 10 pone la campana en la barra de la app, así que va después del 13 para no chocar en el mismo archivo.
- **Por cada cambio:** el explorador hace el mapa, el implementador cambia el código y las pruebas, el tester valida, los revisores revisan y el supervisor aprueba. Cada cambio de módulo sube la versión del módulo y actualiza su README.
- **Pruebas:** `./gradlew :server:test` para servidor y módulos, y `./gradlew :app:shared:compileKotlinJvm` para la app.
