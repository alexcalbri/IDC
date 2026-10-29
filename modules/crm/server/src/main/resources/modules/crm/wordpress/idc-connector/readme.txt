=== IDC Connector ===
Requires at least: 6.0
Requires PHP: 8.0
Stable tag: 0.2.0

Conecta un sitio WordPress con el tema Real Estate 7 al CRM de IdeasCore.

== Que hace ==
* El CRM busca y lee las propiedades (listings) con todos sus campos del tema y las vincula a los leads.
* Los formularios de CT Forms, las consultas de una propiedad y las solicitudes de visita crean leads en el CRM con la propiedad vinculada y su agente sugerido.
* Los favoritos, las busquedas guardadas con alertas y las vistas de propiedades quedan en la actividad del lead.
* Al cerrar una venta en el CRM la propiedad pasa a Sold con la fecha y el precio de venta.
* Exporta los leads de CT Leads Pro (etapa, agente, origen, etiquetas, notas, mensajes y propiedades de interes) para importarlos al CRM.
* reCAPTCHA v3 opcional, verificado en el servidor, para los formularios de CT Forms.

== Configuracion ==
Ajustes > IDC Connector: direccion de IDC, codigo de la empresa y el secreto generado en el CRM (Configuracion > Integraciones > WordPress).
Los eventos que no llegan quedan en cola y se reintentan cada hora.
