# 🖨️ Sistema de Gestión de Impresoras CUPS + Samba

## Servidor de Impresión Empresarial para Ubuntu Server

### Interfaz web moderna para administrar impresoras compartidas vía CUPS y Samba

---

## 📋 Descripción

Sistema integral de gestión de impresión que proporciona:

- **Interfaz Web Moderna**: Gestión completa desde navegador
- **Integración CUPS**: Control total del sistema de impresión Unix/Linux
- **Compartición Samba**: Acceso transparente desde Windows, macOS y Linux
- **Gestión de Departamentos**: Organización jerárquica de recursos
- **Control de Acceso**: Permisos granulares por usuario y departamento
- **Estadísticas**: Monitoreo de uso y trabajos de impresión
- **Descubrimiento Automático**: Detección de impresoras en red

## 🏗️ Arquitectura

```
Clientes (Windows/Linux/Mac)
         ↓
    Red Local
         ↓
  ┌──────────────────┐
  │  Ubuntu Server   │
  │                  │
  │  ┌────────────┐  │
  │  │ Web UI     │  │ ← Interfaz de Administración
  │  │ (8080)     │  │   (Spring Boot + Thymeleaf)
  │  └──────┬─────┘  │
  │         │        │
  │  ┌──────▼─────┐  │
  │  │   CUPS     │  │ ← Sistema de Impresión
  │  │   (631)    │  │   (Colas, Drivers, Jobs)
  │  └──────┬─────┘  │
  │         │        │
  │  ┌──────▼─────┐  │
  │  │   Samba    │  │ ← Compartición de Red
  │  │ (139/445)  │  │   (SMB/CIFS)
  │  └────────────┘  │
  └──────────────────┘
          ↓
    Impresoras
```

## ✨ Características Principales

### 🎯 Gestión de Impresoras
- ✅ Agregar/editar/eliminar impresoras
- ✅ Sincronización automática con CUPS
- ✅ Compartición automática vía Samba
- ✅ Descubrimiento de impresoras en red (SNMP, IPP)
- ✅ Soporte para impresoras locales y de red
- ✅ Configuración de drivers PPD

### 👥 Gestión de Usuarios
- ✅ Sistema de autenticación integrado
- ✅ Usuarios Samba sincronizados
- ✅ Roles y permisos (Admin/Usuario)
- ✅ Control de acceso por departamento

### 🏢 Gestión de Departamentos
- ✅ Organización jerárquica
- ✅ Asignación de impresoras por departamento
- ✅ Control de computadoras autorizadas
- ✅ Estadísticas por departamento

### 📊 Monitoreo y Estadísticas
- ✅ Estado en tiempo real de impresoras
- ✅ Cola de trabajos de impresión
- ✅ Historial de trabajos
- ✅ Estadísticas de uso por usuario/impresora
- ✅ Alertas de estado (sin papel, sin tinta)

### 🌐 Descubrimiento de Red
- ✅ Escaneo de rangos de red
- ✅ Detección automática de impresoras
- ✅ Soporte SNMP
- ✅ Soporte mDNS/Bonjour
- ✅ Configuración de VLANs

## 🚀 Instalación Rápida

### Opción 1: Script Automático (Recomendado)

```bash
# Descargar el repositorio
git clone https://github.com/tu-usuario/print-manager.git
cd print-manager

# Hacer ejecutable el script
chmod +x install-print-server.sh

# Ejecutar instalación
sudo ./install-print-server.sh
```

El script instalará y configurará automáticamente:
- ✅ CUPS
- ✅ Samba
- ✅ PostgreSQL
- ✅ Java 21
- ✅ La aplicación web
- ✅ Firewall
- ✅ Servicios systemd

### Opción 2: Instalación Manual

Consulta la [Guía de Instalación Completa](CUPS-SAMBA-SETUP.md)

## 📱 Acceso a la Aplicación

Después de la instalación:

1. **Interfaz Web**: `http://IP-DEL-SERVIDOR:8080`
   - Usuario: `admin`
   - Password: `admin` (¡Cámbialo inmediatamente!)

2. **CUPS Web Interface**: `http://IP-DEL-SERVIDOR:631`

3. **Impresoras compartidas Samba**: `\\IP-DEL-SERVIDOR\printer-name`

## 🔧 Configuración

### Archivo de Configuración Principal

`src/main/resources/application.properties`:

```properties
# Puerto del servidor
server.port=8080

# Base de datos PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/impre
spring.datasource.username=postgres
spring.datasource.password=1212

# Ruta de archivos
es.ucm.fdi.base-path=./data/uploads
```

### Variables de Entorno (Opcional)

```bash
export SERVER_PORT=8080
export DB_PASSWORD=tu_password_seguro
export CUPS_SERVER=localhost:631
```

## 💻 Uso

### Agregar una Impresora

#### Vía Interfaz Web:
1. Ir a **CUPS/Samba Management**
2. Click en **Import from CUPS** (para importar desde CUPS)
3. O click en **Add Printer** para agregar manualmente
4. Click en **Share via Samba** para compartirla en red

#### Vía Línea de Comandos:
```bash
# Agregar impresora a CUPS
sudo lpadmin -p HP_LaserJet -E -v ipp://192.168.1.100/ipp/print -m everywhere

# La aplicación web detectará automáticamente la nueva impresora
# O puedes importarla desde la interfaz
```

### Compartir Impresora vía Samba

1. En la interfaz web, ir a impresora
2. Click en **Share via Samba**
3. Configurar permisos de acceso
4. Los clientes Windows podrán acceder vía `\\servidor\impresora`

### Crear Departamento

1. Ir a **Departments**
2. Click en **Create Department**
3. Asignar impresoras y computadoras
4. Configurar permisos

### Monitorear Trabajos

1. Ir a **Jobs** o **CUPS/Samba Management**
2. Ver cola de impresión en tiempo real
3. Cancelar trabajos si es necesario
4. Ver historial y estadísticas

## 🛠️ Comandos Útiles

### Estado de Servicios
```bash
# Ver estado de todos los servicios
sudo systemctl status cups smbd print-manager

# Reiniciar servicios
sudo systemctl restart cups smbd nmbd print-manager

# Ver logs en tiempo real
sudo journalctl -u print-manager -f
```

### Gestión de Impresoras CUPS
```bash
# Listar impresoras
lpstat -p -d

# Ver trabajos en cola
lpstat -o

# Cancelar trabajo
cancel printer-name-job-id

# Probar impresión
echo "Test" | lp -d printer-name
```

### Gestión de Usuarios Samba
```bash
# Agregar usuario
sudo smbpasswd -a username

# Listar usuarios
sudo pdbedit -L

# Ver conexiones activas
sudo smbstatus
```

## 📊 API REST

La aplicación proporciona una API REST completa:

```bash
# Login
curl -X POST http://localhost:8080/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# Listar impresoras
curl http://localhost:8080/api/{token}/list

# Ver estado de servicios
curl http://localhost:8080/cups-samba/api/services-status

# Ver trabajos activos
curl http://localhost:8080/cups-samba/api/active-jobs
```

Consulta la [Documentación de API](docs/API.md) para más detalles.

## 🔒 Seguridad

### Recomendaciones:

1. **Cambiar contraseñas por defecto**
   ```bash
   # En la interfaz web: Settings > Change Password
   ```

2. **Configurar firewall**
   ```bash
   sudo ufw enable
   sudo ufw allow from 192.168.1.0/24 to any port 8080
   ```

3. **SSL/TLS para CUPS**
   ```bash
   # Generar certificado
   sudo openssl req -new -x509 -days 365 \
     -keyout /etc/cups/ssl/server.key \
     -out /etc/cups/ssl/server.crt
   ```

4. **Configurar autenticación Samba**
   - Deshabilitar guest access en producción
   - Usar contraseñas seguras
   - Integrar con Active Directory si es posible

## 🐛 Solución de Problemas

### La aplicación no inicia
```bash
# Ver logs
sudo journalctl -u print-manager -n 50

# Verificar Java
java -version

# Verificar PostgreSQL
sudo systemctl status postgresql
```

### CUPS no responde
```bash
# Verificar estado
sudo systemctl status cups

# Ver logs
sudo tail -f /var/log/cups/error_log

# Reiniciar
sudo systemctl restart cups
```

### Samba no comparte impresoras
```bash
# Verificar configuración
testparm

# Ver logs
sudo tail -f /var/log/samba/log.smbd

# Verificar que CUPS esté activo
systemctl status cups
```

Consulta la [Guía de Solución de Problemas](CUPS-SAMBA-SETUP.md#-solución-de-problemas) completa.

## 📚 Documentación

- [Guía de Instalación Completa](CUPS-SAMBA-SETUP.md)
- [Documentación de API](docs/API.md)
- [Configuración Avanzada](docs/ADVANCED.md)
- [Integración con Active Directory](docs/AD-INTEGRATION.md)

## 🤝 Contribuir

Las contribuciones son bienvenidas:

1. Fork el proyecto
2. Crea una rama (`git checkout -b feature/AmazingFeature`)
3. Commit tus cambios (`git commit -m 'Add AmazingFeature'`)
4. Push a la rama (`git push origin feature/AmazingFeature`)
5. Abre un Pull Request

## 📝 Requisitos del Sistema

### Mínimos:
- Ubuntu Server 22.04+
- 2 GB RAM
- 2 CPU cores
- 20 GB disco
- Red 100 Mbps

### Recomendados:
- Ubuntu Server 22.04 LTS
- 4 GB RAM
- 4 CPU cores
- 50 GB disco
- Red 1 Gbps

## 🔖 Tecnologías

- **Backend**: Java 21, Spring Boot 3.5.3
- **Frontend**: Thymeleaf, Bootstrap, JavaScript
- **Base de Datos**: PostgreSQL 14+
- **Sistema de Impresión**: CUPS 2.4+
- **Compartición**: Samba 4.15+
- **Descubrimiento**: SNMP4J

## 📄 Licencia

Este proyecto está bajo la Licencia MIT. Ver el archivo [LICENSE](LICENSE) para más detalles.

## 👥 Autores

- **Equipo de Desarrollo** - *Trabajo Inicial*

## 🙏 Agradecimientos

- Proyecto CUPS
- Proyecto Samba
- Comunidad Spring Boot
- OpenPrinting

## 📞 Soporte

Para obtener ayuda:
- 📧 Email: soporte@tudominio.com
- 🐛 Issues: [GitHub Issues](https://github.com/tu-usuario/print-manager/issues)
- 📖 Wiki: [GitHub Wiki](https://github.com/tu-usuario/print-manager/wiki)

---

**¡Haz que la gestión de impresoras sea fácil! 🖨️✨**
