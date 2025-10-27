---
description: Guía para dividir el panel admin en secciones modulares
alwaysApply: false
---

Cuando dividas el panel de administración en secciones:
- Cada sección debe tener su propia ruta GET en el controlador
- Cada sección debe tener su propia vista HTML
- Todas las vistas deben usar el mismo sidebar (admin-sidebar.html)
- Redirecciona siempre a la sección apropiada después de POST
- Mantén los estilos CSS monocromáticos oscuros (#1a1f2e, #2d3748)
- Usa currentUri en el modelo para resaltar la sección activa en el sidebar