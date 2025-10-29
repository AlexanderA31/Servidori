# 🖨️ Sistema de Gestión de Impresoras

Sistema integral de gestión de impresión empresarial con **Spring Boot** que integra **CUPS** y **Samba** para redes heterogéneas (Windows, Linux, macOS).

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14+-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 🎯 Características Principales

- **🖥️ Interfaz Web Moderna**: Panel de administración responsive con Bootstrap
- **🔌 Servidor IPP Embebido**: Servidor de impresión nativo en Java sin dependencia de CUPS
- **📡 Compartición Samba**: Acceso transparente desde Windows, macOS y Linux
- **🏢 Gestión de Departamentos**: Organización jerárquica con control de acceso por MAC
- **🔍 Descubrimiento Automático**: Detección de impresoras vía SNMP, IPP y mDNS
- **🌐 Gestión de VLANs**: Soporte para múltiples rangos de red CIDR
- **📊 Monitoreo en Tiempo Real**: Dashboard con estadísticas y estado de impresoras
- **📱 API REST**: Endpoints para integraciones externas

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────┐
│  Clientes (Windows/Linux/macOS)         │
└──────────────┬──────────────────────────┘
               │
       ┌───────▼────────┐
       │  Red LAN/VLAN  │
       └───────┬────────┘
               │
┌──────────────▼───────────────────────────┐
│      UBUNTU SERVER (Java 21)             │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │  Web UI + REST API (Puerto 8080)   │  │
│  └────────────┬───────────────────────┘  │
│               │                          │
│  ┌────────────▼───────────────────────┐  │
│  │  Services (Business Logic)         │  │
│  │  • PrinterDiscoveryService         │  │
│  │  • IppServerService                │  │
│  │  • PrintQueueService               │  │
│  │  • SambaService                    │  │
│  └────────────┬───────────────────────┘  │
│               │                          │
│  ┌────────────▼───────────────────────┐  │
│  │  PostgreSQL Database               │  │
│  └────────────────────────────────────┘  │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │  Samba (SMB/CIFS)                  │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

## 📦 Modelo de Datos

### Entidades Principales

- **User**: Usuarios con roles ADMIN/USER
- **Printer**: Impresoras con soporte IPP/RAW/LPD/SMB
- **Job**: Trabajos de impresión en cola
- **Department**: Departamentos con impresoras y computadoras asignadas
- **Computer**: Autenticación por dirección MAC
- **NetworkRange**: Rangos CIDR para descubrimiento automático

## 🚀 Instalación

### Requisitos Previos

- Ubuntu Server 22.04+ LTS
- Java 21
- PostgreSQL 14+
- Maven 3.8+
- Samba (opcional para compartir impresoras)

### Instalación Rápida

```bash
# 1. Clonar repositorio
git clone https://github.com/tu-usuario/print-manager.git
cd print-manager

# 2. Configurar PostgreSQL
sudo -u postgres psql
CREATE DATABASE impre;
CREATE USER postgres WITH PASSWORD '1212';
GRANT ALL PRIVILEGES ON DATABASE impre TO postgres;
\q

# 3. Compilar y ejecutar
mvn clean package
java -jar target/iu-0.0.1-SNAPSHOT.jar
```

La aplicación estará disponible en `http://localhost:8080`

### Credenciales por Defecto

- **Usuario**: `admin`
- **Contraseña**: `admin` (⚠️ Cámbiala inmediatamente)

## ⚙️ Configuración

### Archivo: `src/main/resources/application.properties`

```properties
# Puerto del servidor
server.port=8080

# Base de datos PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/impre
spring.datasource.username=postgres
spring.datasource.password=1212

# Ruta de archivos
es.ucm.fdi.base-path=./data/uploads

# Redes a escanear (CIDR)
printer.discovery.networks=10.1.1.0/24,10.1.4.0/24,192.168.1.0/24

# Descubrimiento SNMP
printer.discovery.snmp.enabled=true
printer.discovery.snmp.community=public
printer.discovery.snmp.timeout=5000

# Configuración Samba
samba.guest.access=yes
samba.browseable=yes
```

## 💻 Uso

### Agregar Impresora Manualmente

1. Acceder a **Impresoras** en el panel admin
2. Click en **Agregar Impresora**
3. Completar: Nombre, IP, Puerto, Protocolo (IPP/RAW/LPD)
4. Guardar

### Descubrimiento Automático

1. Ir a **Descubrimiento de Red**
2. Configurar rangos de red (ej: 192.168.1.0/24)
3. Click en **Escanear Red**
4. Seleccionar impresoras detectadas
5. **Agregar Seleccionadas**

### Compartir vía Samba

1. Seleccionar impresora
2. Click en **Compartir vía Samba**
3. Clientes Windows acceden vía `\\SERVIDOR\nombre-impresora`

### Gestión de Departamentos

1. Ir a **Departamentos**
2. **Crear Departamento**
3. Asignar impresoras y computadoras (por MAC)
4. Solo computadoras autorizadas podrán imprimir

## 📊 API REST

### Autenticación

```bash
# Login
curl -X POST http://localhost:8080/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'
# Respuesta: {"token":"abc123..."}
```

### Endpoints Principales

```bash
# Listar impresoras
curl http://localhost:8080/api/{token}/list

# Ver trabajos activos
curl http://localhost:8080/api/{token}/jobs

# Estado de servicios
curl http://localhost:8080/cups-samba/api/services-status
```

## 🔧 Comandos Útiles

### Gestión de Servicios

```bash
# Ver logs de la aplicación
sudo journalctl -u print-manager -f

# Reiniciar servicios
sudo systemctl restart print-manager
sudo systemctl restart smbd nmbd
```

### Gestión de Samba

```bash
# Agregar usuario Samba
sudo smbpasswd -a username

# Listar usuarios
sudo pdbedit -L

# Ver conexiones activas
sudo smbstatus

# Verificar configuración
testparm
```

## 📁 Estructura del Proyecto

```
src/main/
├── java/es/ucm/fdi/iu/
│   ├── control/          # Controladores MVC y API REST
│   ├── service/          # Lógica de negocio
│   ├── model/            # Entidades JPA
│   ├── repository/       # Repositorios Spring Data
│   ├── util/             # Utilidades (NetworkUtils)
│   └── SecurityConfig.java
│
└── resources/
    ├── templates/        # Vistas Thymeleaf
    │   ├── login.html
    │   ├── admin-dashboard.html
    │   ├── admin-printers.html
    │   ├── admin-departments.html
    │   └── fragments/
    │
    ├── static/
    │   ├── css/          # Bootstrap + estilos personalizados
    │   └── js/           # jQuery + scripts admin
    │
    └── application.properties
```

## 🛠️ Stack Tecnológico

### Backend
- **Java 21** (Virtual Threads, Records)
- **Spring Boot 3.5.3** (Web MVC, Data JPA, Security)
- **PostgreSQL 14+** (Base de datos)
- **Hibernate 6.x** (ORM)
- **SNMP4J 3.7.7** (Descubrimiento de red)
- **Apache PDFBox 2.0.30** (Procesamiento PDF)
- **JCIFS-NG 2.1.9** (SMB/CIFS en Java)

### Frontend
- **Thymeleaf 3.x** (Template Engine)
- **Bootstrap 4.5.3** (Framework CSS)
- **jQuery 3.3.1** (JavaScript)

### Protocolos Soportados
- **IPP** (Puerto 631)
- **RAW/Socket** (Puerto 9100)
- **LPD** (Puerto 515)
- **SMB/CIFS** (Puertos 139, 445)
- **SNMP** (Puerto 161) - Descubrimiento
- **mDNS** (Puerto 5353) - Bonjour/Avahi

## 🐛 Solución de Problemas

### La aplicación no inicia

```bash
# Ver logs completos
sudo journalctl -u print-manager -n 100

# Verificar Java
java -version  # Debe ser 21

# Verificar PostgreSQL
sudo systemctl status postgresql
```

### No detecta impresoras

```bash
# Verificar conectividad
ping 192.168.1.100

# Probar SNMP manualmente
snmpwalk -v2c -c public 192.168.1.100

# Verificar puertos abiertos
nmap -p 161,9100,631 192.168.1.100
```

### Samba no comparte

```bash
# Ver logs Samba
sudo tail -f /var/log/samba/log.smbd

# Verificar configuración
testparm

# Reiniciar Samba
sudo systemctl restart smbd nmbd
```

## 📝 Requisitos del Sistema

### Mínimos
- **OS**: Ubuntu Server 22.04+
- **CPU**: 2 cores
- **RAM**: 2 GB
- **Disco**: 20 GB

### Recomendados para Producción
- **OS**: Ubuntu Server 22.04 LTS
- **CPU**: 4 cores (2.0+ GHz)
- **RAM**: 4-8 GB
- **Disco**: 50 GB SSD
- **Red**: 1 Gbps

## 🔒 Seguridad

### Recomendaciones

1. **Cambiar contraseña admin** inmediatamente
2. **Configurar firewall**:
   ```bash
   sudo ufw enable
   sudo ufw allow 8080/tcp
   sudo ufw allow 445/tcp
   ```
3. **Usar HTTPS** con certificado SSL/TLS
4. **Limitar acceso** por IP si es posible
5. **Actualizar** dependencias regularmente

## 📄 Licencia

Proyecto bajo Licencia MIT. Ver [LICENSE](LICENSE) para detalles.

## 🙏 Agradecimientos

- Proyecto CUPS
- Proyecto Samba
- Comunidad Spring Boot
- OpenPrinting

---

**¡Gestión de impresoras simplificada! 🖨️✨**
