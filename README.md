# 🖨️ Sistema de Gestión de Impresoras CUPS + Samba

## Servidor de Impresión Empresarial para Ubuntu Server

### Interfaz web moderna para administrar impresoras compartidas vía CUPS y Samba

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14+-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📋 Descripción

Sistema integral de gestión de impresión empresarial desarrollado con **Spring Boot** que integra **CUPS** (Common Unix Printing System) y **Samba** para proporcionar una solución completa de servidor de impresión para redes heterogéneas (Windows, Linux, macOS).

### Características Principales

- **🖥️ Interfaz Web Moderna**: Gestión completa desde navegador con diseño responsive
- **🔌 Servidor IPP Embebido**: Servidor de impresión Internet Printing Protocol nativo en Java
- **🔄 Integración CUPS**: Control total del sistema de impresión Unix/Linux (opcional)
- **📡 Compartición Samba**: Acceso transparente desde Windows, macOS y Linux
- **🏢 Gestión de Departamentos**: Organización jerárquica de recursos por áreas
- **🔐 Control de Acceso**: Autenticación por direcciones MAC y permisos granulares
- **📊 Estadísticas en Tiempo Real**: Monitoreo de uso, trabajos y estado de impresoras
- **🔍 Descubrimiento Automático**: Detección de impresoras en red vía SNMP, IPP y mDNS
- **🌐 Gestión de VLANs**: Configuración de múltiples rangos de red
- **📱 API REST Completa**: Integración con sistemas externos

## 🏗️ Arquitectura del Sistema

### Diagrama de Arquitectura General

```
┌─────────────────────────────────────────────────────────────────┐
│                     CLIENTES DE RED                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│  │   Windows    │  │    Linux     │  │    macOS     │         │
│  │   Clients    │  │   Clients    │  │   Clients    │         │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘         │
└─────────┼──────────────────┼──────────────────┼─────────────────┘
          │                  │                  │
          └──────────────────┼──────────────────┘
                             │
                    ┌────────▼─────────┐
                    │   Red LAN/VLAN   │
                    │  (192.168.x.x)   │
                    └────────┬─────────┘
                             │
┌────────────────────────────▼──────────────────────────────────┐
│                    UBUNTU SERVER (Java 21)                    │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │         CAPA DE PRESENTACIÓN (Puerto 8080)               │ │
│  │  ┌────────────────┐  ┌────────────────┐                 │ │
│  │  │  Web Interface │  │   REST API     │                 │ │
│  │  │   (Thymeleaf)  │  │   Endpoints    │                 │ │
│  │  └────────┬───────┘  └────────┬───────┘                 │ │
│  └───────────┼────────────────────┼─────────────────────────┘ │
│              │                    │                           │
│  ┌───────────▼────────────────────▼─────────────────────────┐ │
│  │              CAPA DE NEGOCIO (Services)                  │ │
│  │  ┌──────────────────────────────────────────────────┐   │ │
│  │  │ • PrinterDiscoveryService (SNMP/IPP/mDNS)       │   │ │
│  │  │ • IppServerService (Servidor IPP Embebido)      │   │ │
│  │  │ • PrintQueueService (Gestión de Colas)          │   │ │
│  │  │ • CupsService (Integración CUPS - Opcional)     │   │ │
│  │  │ • SambaService (Gestión Usuarios Samba)         │   │ │
│  │  │ • SmbShareService (Compartir Impresoras)        │   │ │
│  │  │ • PrinterAutoConfigService (Configuración Auto) │   │ │
│  │  └──────────────────────────────────────────────────┘   │ │
│  └───────────────────────────┬──────────────────────────────┘ │
│                              │                                │
│  ┌───────────────────────────▼──────────────────────────────┐ │
│  │         CAPA DE PERSISTENCIA (Spring Data JPA)           │ │
│  │  ┌────────────────────────────────────────────────────┐  │ │
│  │  │ • UserRepository    • PrinterRepository           │  │ │
│  │  │ • JobRepository     • DepartmentRepository        │  │ │
│  │  │ • ComputerRepository • NetworkRangeRepository     │  │ │
│  │  └────────────────────────────────────────────────────┘  │ │
│  └───────────────────────────┬──────────────────────────────┘ │
│                              │                                │
│  ┌───────────────────────────▼──────────────────────────────┐ │
│  │            PostgreSQL Database (Puerto 5432)             │ │
│  │  ┌────────────────────────────────────────────────────┐  │ │
│  │  │ • users • printers • jobs • departments            │  │ │
│  │  │ • computers • network_ranges • tokens              │  │ │
│  │  └────────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │     SERVICIOS EXTERNOS (Integración Sistema)             │ │
│  │  ┌────────────────┐  ┌────────────────┐                 │ │
│  │  │  CUPS Service  │  │ Samba Service  │                 │ │
│  │  │  (Puerto 631)  │  │ (Puertos 139,  │                 │ │
│  │  │   [Opcional]   │  │     445)       │                 │ │
│  │  └────────────────┘  └────────────────┘                 │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────────┬───────────────────────────────────────┘
                         │
              ┌──────────▼──────────┐
              │   Red de Impresoras │
              │  ┌────┐  ┌────┐    │
              │  │ HP │  │EPSON│   │
              │  └────┘  └────┘    │
              └─────────────────────┘
```

### Arquitectura de Capas

#### 1. **Capa de Presentación**
- **Controladores Web**: Gestión de vistas HTML con Thymeleaf
- **REST API**: Endpoints JSON para integraciones externas
- **Autenticación**: Spring Security con sesiones
- **Assets Estáticos**: CSS, JavaScript, Bootstrap

#### 2. **Capa de Negocio (Servicios)**
- **PrinterDiscoveryService**: Descubrimiento automático de impresoras (SNMP, IPP, mDNS)
- **IppServerService**: Servidor IPP embebido para recibir trabajos de impresión
- **PrintQueueService**: Gestión de colas de impresión y procesamiento de trabajos
- **CupsService**: Integración con CUPS del sistema (deprecado/opcional)
- **SambaService**: Sincronización de usuarios con Samba
- **SmbShareService**: Compartición de impresoras vía SMB/CIFS
- **PrinterAutoConfigService**: Configuración automática de impresoras detectadas

#### 3. **Capa de Persistencia**
- **Spring Data JPA**: ORM para mapeo objeto-relacional
- **Hibernate**: Implementación de JPA
- **Repositorios**: Interfaces para acceso a datos

#### 4. **Capa de Datos**
- **PostgreSQL**: Base de datos relacional principal
- **Esquema**: Diseño normalizado con relaciones many-to-many

### Protocolos de Comunicación

```
┌─────────────────────────────────────────────────────────────┐
│                   PROTOCOLOS SOPORTADOS                     │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  🖨️ IMPRESIÓN                                               │
│  • IPP (Internet Printing Protocol) - Puerto 631           │
│  • RAW/Socket - Puerto 9100                                 │
│  • LPD (Line Printer Daemon) - Puerto 515                   │
│  • SMB/CIFS (Windows Sharing) - Puertos 139, 445           │
│                                                             │
│  🔍 DESCUBRIMIENTO                                           │
│  • SNMP (Simple Network Management Protocol) - Puerto 161   │
│  • mDNS/Bonjour - Puerto 5353                               │
│  • IPP over HTTP/HTTPS                                      │
│                                                             │
│  🔐 SEGURIDAD                                                │
│  • HTTPS/TLS para comunicaciones seguras                    │
│  • Autenticación por dirección MAC                          │
│  • Spring Security para control de acceso                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 📦 Modelo de Datos

### Diagrama Entidad-Relación

```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│    User     │         │    Printer   │         │     Job     │
├─────────────┤         ├──────────────┤         ├─────────────┤
│ id          │1      * │ id           │1      * │ id          │
│ username    │─────────│ instance     │─────────│ instance    │
│ password    │         │ alias        │         │ printer     │
│ roles       │         │ model        │         │ owner       │
│ enabled     │         │ location     │         │ fileName    │
└─────────────┘         │ ip           │         └─────────────┘
                        │ deviceUri    │
                        │ port         │
                        │ protocol     │
                        │ ink          │
                        │ paper        │
                        └──────┬───────┘
                               │ *
                               │
                               │ *
                        ┌──────▼───────┐
                        │ Department   │
                        ├──────────────┤
                        │ id           │
                        │ name         │
         ┌──────────────│ description  │
         │              │ location     │
         │              │ color        │
         │              └──────┬───────┘
         │ *                   │ 1
         │                     │
         │                     │ *
  ┌──────▼────────┐     ┌──────▼─────────┐
  │   Computer    │     │ NetworkRange   │
  ├───────────────┤     ├────────────────┤
  │ id            │     │ id             │
  │ macAddress    │     │ name           │
  │ name          │     │ cidrRange      │
  │ hostname      │     │ vlanId         │
  │ location      │     │ active         │
  │ authorized    │     │ lastScan       │
  │ lastConnection│     └────────────────┘
  └───────────────┘
```

### Entidades Principales

#### 1. **User** (Usuarios del Sistema)
- **Campos**: id, username, password, roles, enabled
- **Relaciones**: 
  - 1:N con Token (sesiones)
  - 1:N con Job, Printer, PGroup (recursos propios)
- **Roles**: USER (usuario normal), ADMIN (administrador)

#### 2. **Printer** (Impresoras)
- **Campos**: 
  - **Identificación**: id, alias, model, location
  - **Conexión**: ip, deviceUri, port, protocol
  - **Estado**: ink, paper, status (PRINTING, PAUSED, NO_INK, NO_PAPER)
- **Relaciones**:
  - N:1 con User (propietario)
  - 1:N con Job (cola de trabajos)
  - N:M con Department (departamentos asignados)
- **Protocolos soportados**: RAW (9100), IPP (631), LPD (515), SMB

#### 3. **Job** (Trabajos de Impresión)
- **Campos**: id, printer, owner, fileName
- **Relaciones**:
  - N:1 con User (propietario)
  - N:1 con Printer (impresora destino)
- **Estados**: Pendiente, Imprimiendo, Completado, Error

#### 4. **Department** (Departamentos)
- **Campos**: id, name, description, location, color
- **Relaciones**:
  - N:1 con User (creador)
  - 1:N con Computer (computadoras del departamento)
  - N:M con Printer (impresoras compartidas)
- **Propósito**: Organizar recursos por áreas (Ventas, IT, Administración, etc.)

#### 5. **Computer** (Computadoras Autorizadas)
- **Campos**: 
  - **Identificación**: id, macAddress (único), name, hostname
  - **Estado**: location, authorized, lastConnection
- **Relaciones**:
  - N:1 con Department (departamento al que pertenece)
- **Autenticación**: Por dirección MAC (formato XX:XX:XX:XX:XX:XX)

#### 6. **NetworkRange** (Rangos de Red/VLANs)
- **Campos**: id, name, cidrRange, vlanId, active, lastScan, lastFoundPrinters
- **Formato CIDR**: 192.168.1.0/24, 10.0.0.0/8, etc.
- **Propósito**: Configurar redes para descubrimiento automático de impresoras

## ✨ Características Principales

### 🎯 Gestión de Impresoras
- ✅ **CRUD Completo**: Agregar, editar, eliminar y visualizar impresoras
- ✅ **Múltiples Protocolos**: RAW/Socket (9100), IPP (631), LPD (515), SMB
- ✅ **Configuración Automática**: Detección y configuración auto de drivers
- ✅ **Sincronización CUPS**: Importar/exportar desde CUPS del sistema
- ✅ **Compartición Samba**: Compartir automáticamente vía SMB/CIFS
- ✅ **Monitoreo en Tiempo Real**: Estado de tinta, papel y trabajos
- ✅ **Soporte Local y Red**: Impresoras USB, red IP, y compartidas

### 👥 Gestión de Usuarios y Seguridad
- ✅ **Autenticación Robusta**: Spring Security con BCrypt
- ✅ **Roles Granulares**: Admin (gestión total) y User (uso básico)
- ✅ **Integración Samba**: Sincronización automática de usuarios Samba
- ✅ **Tokens de Sesión**: Sistema de tokens para API REST
- ✅ **Control MAC Address**: Autenticación de computadoras por MAC
- ✅ **Auditoría**: Registro de accesos y operaciones

### 🏢 Gestión de Departamentos
- ✅ **Organización Jerárquica**: Estructura por áreas de trabajo
- ✅ **Asignación de Recursos**: Impresoras por departamento
- ✅ **Control de Acceso**: Solo computadoras autorizadas pueden imprimir
- ✅ **Identificación Visual**: Colores personalizados por departamento
- ✅ **Estadísticas Departamentales**: Uso por área organizativa
- ✅ **Ubicación Física**: Gestión de locaciones

### 📊 Monitoreo y Estadísticas
- ✅ **Dashboard en Tiempo Real**: Estado actual de todas las impresoras
- ✅ **Cola de Impresión**: Visualización y gestión de trabajos pendientes
- ✅ **Historial Completo**: Registro de todos los trabajos procesados
- ✅ **Estadísticas por Usuario**: Uso individual de recursos
- ✅ **Estadísticas por Impresora**: Trabajos procesados, páginas, etc.
- ✅ **Alertas Automáticas**: Notificaciones de sin papel, sin tinta, errores
- ✅ **Reportes**: Exportación de datos de uso

### 🌐 Descubrimiento de Red
- ✅ **Escaneo SNMP**: Detección via Simple Network Management Protocol
- ✅ **Descubrimiento IPP**: Búsqueda de impresoras Internet Printing Protocol
- ✅ **mDNS/Bonjour**: Detección automática en red local
- ✅ **Configuración de VLANs**: Múltiples rangos de red (CIDR)
- ✅ **Escaneo Programado**: Búsqueda automática periódica
- ✅ **Filtrado Inteligente**: Exclusión de dispositivos no deseados

## 📁 Estructura del Proyecto

```
print-manager/
├── src/
│   ├── main/
│   │   ├── java/es/ucm/fdi/iu/
│   │   │   ├── control/              # Controladores MVC
│   │   │   │   ├── RootController.java          # Página principal
│   │   │   │   ├── AdminController.java         # Panel admin
│   │   │   │   ├── ApiController.java           # API REST
│   │   │   │   ├── CupsSambaController.java     # Gestión CUPS/Samba
│   │   │   │   ├── DepartmentController.java    # Departamentos
│   │   │   │   ├── NetworkManagementController.java  # VLANs
│   │   │   │   └── PrinterServerController.java # Servidor IPP
│   │   │   │
│   │   │   ├── service/              # Lógica de negocio
│   │   │   │   ├── PrinterDiscoveryService.java # Descubrimiento
│   │   │   │   ├── IppServerService.java        # Servidor IPP
│   │   │   │   ├── PrintQueueService.java       # Colas
│   │   │   │   ├── CupsService.java             # CUPS [Deprecado]
│   │   │   │   ├── SambaService.java            # Samba users
│   │   │   │   ├── SmbShareService.java         # Compartir SMB
│   │   │   │   └── PrinterAutoConfigService.java # Auto-config
│   │   │   │
│   │   │   ├── model/                # Entidades JPA
│   │   │   │   ├── User.java                    # Usuarios
│   │   │   │   ├── Printer.java                 # Impresoras
│   │   │   │   ├── Job.java                     # Trabajos
│   │   │   │   ├── Department.java              # Departamentos
│   │   │   │   ├── Computer.java                # Computadoras
│   │   │   │   ├── NetworkRange.java            # VLANs/Rangos
│   │   │   │   ├── Token.java                   # Tokens API
│   │   │   │   └── PGroup.java                  # Grupos
│   │   │   │
│   │   │   ├── repository/           # Repositorios Spring Data
│   │   │   │   └── PrinterRepository.java
│   │   │   │
│   │   │   ├── util/                 # Utilidades
│   │   │   │   └── NetworkUtils.java            # Red/CIDR
│   │   │   │
│   │   │   ├── SecurityConfig.java           # Spring Security
│   │   │   ├── PmgrApplication.java          # Main class
│   │   │   ├── DataInitializer.java          # Datos iniciales
│   │   │   └── StartupConfig.java            # Configuración inicio
│   │   │
│   │   └── resources/
│   │       ├── templates/            # Vistas Thymeleaf
│   │       │   ├── login.html
│   │       │   ├── admin-dashboard.html
│   │       │   ├── admin-printers.html
│   │       │   ├── admin-departments.html
│   │       │   ├── admin-computers.html
│   │       │   ├── admin-printqueues.html
│   │       │   ├── print-server.html
│   │       │   └── fragments/            # Fragmentos reutilizables
│   │       │
│   │       ├── static/               # Recursos estáticos
│   │       │   ├── css/              # Hojas de estilo
│   │       │   │   ├── bootstrap.css
│   │       │   │   ├── admin-modern.css
│   │       │   │   ├── admin-dashboard.css
│   │       │   │   ├── admin-printers.css
│   │       │   │   ├── admin-departments.css
│   │       │   │   └── ...
│   │       │   │
│   │       │   └── js/               # JavaScript
│   │       │       ├── jquery-3.3.1.js
│   │       │       ├── bootstrap.bundle.js
│   │       │       ├── pmgr.js
│   │       │       ├── pmgrapi.js
│   │       │       ├── admin.js
│   │       │       └── network-print.js
│   │       │
│   │       ├── application.properties    # Configuración
│   │       ├── import.sql                # Datos iniciales SQL
│   │       └── messages.properties       # Internacionalización
│   │
│   └── test/                     # Tests unitarios
│
├── scripts/                      # Scripts auxiliares
│   ├── add-printer-linux.sh
│   └── add-printer-windows.ps1
│
├── data/                         # Datos de aplicación
│   └── uploads/                  # Archivos subidos
│
├── create-tables.sql             # Script creación tablas
├── fix-printer-columns.sql       # Migraciones
├── fix_admin_password.sql        # Utils DB
│
├── pom.xml                       # Maven config
├── lombok.config                 # Lombok config
├── README.md                     # Este archivo
└── LICENSE                       # Licencia MIT
```

### Componentes Clave

#### 🔹 **Backend (Java/Spring Boot)**

**Controladores**:
- `RootController`: Página principal y navegación
- `AdminController`: Panel de administración completo
- `ApiController`: API REST para integraciones
- `CupsSambaController`: Gestión de CUPS y Samba
- `DepartmentController`: CRUD de departamentos
- `NetworkManagementController`: Gestión de VLANs
- `PrinterServerController`: Servidor IPP embebido

**Servicios**:
- `PrinterDiscoveryService`: 
  - Descubrimiento SNMP (puerto 161)
  - Descubrimiento mDNS/Bonjour
  - Detección IPP
  - Escaneo de rangos CIDR
  
- `IppServerService`: 
  - Servidor IPP/1.1 embebido
  - Recepción de trabajos de impresión
  - Procesamiento de operaciones IPP
  
- `PrintQueueService`:
  - Gestión de colas por impresora
  - Procesamiento de trabajos
  - Control de estado (pausar/reanudar)
  
- `SambaService`:
  - Sincronización de usuarios
  - Gestión de contraseñas Samba
  - Integración con pdbedit
  
- `SmbShareService`:
  - Compartir impresoras vía SMB/CIFS
  - Configuración de smb.conf
  - Permisos de acceso

**Seguridad**:
- `SecurityConfig`: Configuración Spring Security
- `IwUserDetailsService`: Carga de usuarios desde DB
- `LoginSuccessHandler`: Manejo de login exitoso
- `AuthenticationLogger`: Auditoría de accesos

#### 🔹 **Frontend (Thymeleaf + Bootstrap)**

**Vistas Principales**:
- `login.html`: Página de inicio de sesión
- `admin-dashboard.html`: Dashboard con estadísticas
- `admin-printers.html`: Gestión de impresoras
- `admin-departments.html`: Gestión de departamentos
- `admin-computers.html`: Computadoras autorizadas
- `admin-printqueues.html`: Colas de impresión
- `print-server.html`: Configuración servidor IPP

**CSS Modular** (en `/static/css/`):
- Cada vista tiene su propio archivo CSS
- `admin-modern.css`: Tema global admin
- `admin-sidebar.css`: Menú lateral
- Sin estilos inline (buena práctica)

**JavaScript**:
- `pmgrapi.js`: Cliente API REST
- `admin.js`: Funciones admin generales
- `network-print.js`: Descubrimiento de red
- jQuery 3.3.1 + Bootstrap 4.5.3

#### 🔹 **Base de Datos (PostgreSQL)**

**Tablas**:
- `user_table`: Usuarios del sistema
- `printer`: Impresoras configuradas
- `job`: Trabajos de impresión
- `department`: Departamentos
- `computer`: Computadoras autorizadas
- `network_range`: Rangos de red/VLANs
- `token`: Tokens de sesión API
- `pgroup`: Grupos de impresoras
- `department_printer`: Tabla de unión (Many-to-Many)

**Índices**:
- Índice en `computer.mac_address` (unique)
- Índice en `user_table.username`
- Índices en claves foráneas

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

## 🔖 Stack Tecnológico Completo

### Backend

#### Framework Principal
- **Java 21** (LTS - Long Term Support)
  - Virtual Threads (Project Loom)
  - Pattern Matching
  - Records
  - Sealed Classes
  
- **Spring Boot 3.5.3**
  - Spring Web MVC
  - Spring Data JPA
  - Spring Security 6
  - Spring Boot Actuator (monitoreo)

#### Persistencia
- **PostgreSQL 14+** - Base de datos principal
- **Hibernate 6.x** - ORM (Object-Relational Mapping)
- **HikariCP** - Connection Pool (alto rendimiento)
- **Flyway** - Migraciones de base de datos (opcional)

#### Seguridad
- **Spring Security 6**
  - Autenticación basada en formularios
  - BCrypt password hashing
  - CSRF protection
  - Session management
- **Thymeleaf Spring Security Extras** - Integración vistas

#### Librerías de Impresión y Red
- **SNMP4J 3.7.7** - Descubrimiento vía SNMP
  - Consulta de impresoras en red
  - MIB (Management Information Base) parsing
  - Trap handling
  
- **Apache PDFBox 2.0.30** - Procesamiento PDF
  - Generación de PDFs
  - Conversión de documentos
  - Extracción de texto
  
- **Apache HttpClient 5** - Cliente HTTP/IPP
  - Conexiones a impresoras IPP
  - HTTP/2 support
  - Connection pooling
  
- **JCIFS-NG 2.1.9** - Protocolo SMB/CIFS en Java puro
  - Compartición de archivos Samba
  - Autenticación NTLM
  - No requiere librerías nativas

#### Utilidades
- **Lombok** - Reducción de boilerplate code
  - @Data, @Getter, @Setter
  - @Builder, @AllArgsConstructor
  - @Slf4j para logging
  
- **Gson** - Serialización/Deserialización JSON
- **Jackson** - JSON processing (incluido con Spring)
- **Log4j 2** - Logging (vía Spring Boot Starter)

### Frontend

#### Template Engine
- **Thymeleaf 3.x**
  - Natural templating
  - Integración con Spring
  - Fragments y layouts
  - Expresiones Spring EL

#### CSS Framework
- **Bootstrap 4.5.3**
  - Grid system responsive
  - Componentes UI
  - Iconos (Font Awesome compatible)
  
#### JavaScript
- **jQuery 3.3.1** - Manipulación DOM y AJAX
- **Bootstrap.js** - Componentes interactivos
- **Custom JS**:
  - `pmgrapi.js` - Cliente API
  - `admin.js` - Lógica administrativa
  - `network-print.js` - Descubrimiento de red

#### Estilos
- CSS modular organizado por página
- Variables CSS para consistencia
- Responsive design
- Sin estilos inline

### Base de Datos

#### PostgreSQL
- **Versión**: 14 o superior
- **Características usadas**:
  - Transacciones ACID
  - Índices B-tree
  - Constraints (FK, Unique, Check)
  - Sequences para IDs
  - Named Queries (JPA)

#### Esquema
```sql
-- Tablas principales
user_table, printer, job, department, computer, 
network_range, token, pgroup

-- Tablas de relación
department_printer (Many-to-Many)
printer_pgroup (Many-to-Many)

-- Secuencia global
gen (SEQUENCE) - IDs unificados
```

### Sistemas Externos

#### CUPS (Common Unix Printing System)
- **Versión**: 2.4+ 
- **Puerto**: 631 (IPP)
- **Uso**: Opcional (sistema tiene IPP embebido)
- **Integración**: 
  - Comandos: `lpadmin`, `lpstat`, `lp`, `cancel`
  - API: libcups (vía process execution)

#### Samba
- **Versión**: 4.15+
- **Puertos**: 139 (NetBIOS), 445 (SMB)
- **Uso**: Compartir impresoras en red Windows
- **Integración**:
  - Comandos: `smbpasswd`, `pdbedit`, `testparm`
  - Configuración: `/etc/samba/smb.conf`
  - Servicio: `smbd`, `nmbd`

### Herramientas de Desarrollo

#### Build Tool
- **Apache Maven 3.8+**
  - Gestión de dependencias
  - Ciclo de vida de build
  - Plugins: compiler, spring-boot, surefire

#### Control de Versiones
- **Git** - Sistema de control de versiones
- **GitHub** - Hosting y colaboración

#### IDE Recomendados
- **IntelliJ IDEA** (Ultimate o Community)
- **Eclipse** con Spring Tools
- **VS Code** con extensiones Java

### Infraestructura y Despliegue

#### Sistema Operativo
- **Ubuntu Server 22.04 LTS** (recomendado)
- **Debian 11+** (compatible)
- **RHEL/CentOS 8+** (con ajustes)

#### Servidor de Aplicaciones
- **Tomcat embebido** (incluido en Spring Boot)
- Puerto por defecto: 8080
- Puede desplegarse como WAR en Tomcat externo

#### Systemd Service
- Servicio: `print-manager.service`
- Auto-inicio en boot
- Restart automático en fallos
- Logs vía journalctl

#### Firewall
- **UFW** (Uncomplicated Firewall)
- Puertos abiertos:
  - 8080 (aplicación web)
  - 631 (CUPS/IPP)
  - 139, 445 (Samba)
  - 22 (SSH admin)

### Protocolos de Red

#### Impresión
- **IPP** (Internet Printing Protocol) - RFC 2910
  - Puerto: 631
  - Transporte: HTTP
  - Operaciones: Print-Job, Get-Jobs, Cancel-Job, etc.
  
- **RAW/Socket**
  - Puerto: 9100 (estándar de facto)
  - Protocolo: TCP directo
  - Más rápido, menos overhead
  
- **LPD** (Line Printer Daemon) - RFC 1179
  - Puerto: 515
  - Protocolo legado Unix
  
- **SMB/CIFS**
  - Puertos: 139, 445
  - Protocolo Windows

#### Descubrimiento
- **SNMP** (Simple Network Management Protocol)
  - Puerto: 161 (consultas)
  - Puerto: 162 (traps)
  - Versión: SNMPv1, SNMPv2c
  
- **mDNS** (Multicast DNS)
  - Puerto: 5353
  - Protocolo: UDP multicast
  - También conocido como Bonjour/Avahi

### Patrones de Diseño Utilizados

1. **MVC** (Model-View-Controller)
   - Model: Entidades JPA
   - View: Templates Thymeleaf
   - Controller: Spring Controllers

2. **Repository Pattern**
   - Abstracción de acceso a datos
   - Spring Data JPA repositories

3. **Service Layer**
   - Lógica de negocio separada
   - Transacciones @Transactional

4. **DTO** (Data Transfer Object)
   - Clases Transfer internas
   - Separación de entidades y API

5. **Dependency Injection**
   - Injección vía constructor
   - Spring @Autowired

6. **Builder Pattern**
   - Lombok @Builder
   - Construcción fluida de objetos

### Requisitos de Sistema

#### Mínimos
- **SO**: Ubuntu Server 22.04+ LTS
- **CPU**: 2 cores x64
- **RAM**: 2 GB
- **Disco**: 20 GB
- **Red**: 100 Mbps
- **Java**: OpenJDK 21
- **PostgreSQL**: 14+

#### Recomendados para Producción
- **SO**: Ubuntu Server 22.04 LTS
- **CPU**: 4 cores x64 (2.0+ GHz)
- **RAM**: 4-8 GB
- **Disco**: 50 GB SSD
- **Red**: 1 Gbps
- **Backup**: Automatizado diario
- **Monitoring**: Prometheus + Grafana

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
