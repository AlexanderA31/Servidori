# Guía de Configuración del Sistema de Gestión de Impresoras CUPS + Samba

## 📋 Descripción

Este sistema proporciona una interfaz web moderna para gestionar un servidor de impresión basado en **CUPS** (Common Unix Printing System) y **Samba** en Ubuntu Server.

## 🏗️ Arquitectura del Sistema

```
┌─────────────────────────────────────────┐
│    Clientes Windows/Linux/Mac           │
│    (Imprimen vía red - SMB/IPP)         │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│         Ubuntu Server 22.04+            │
│ ┌─────────────────────────────────────┐ │
│ │  Aplicación Web (Spring Boot)       │ │
│ │  - Puerto 8080                      │ │
│ │  - Java 21, Thymeleaf, PostgreSQL  │ │
│ └──────────┬──────────────────────────┘ │
│            │                             │
│ ┌──────────▼──────────────────────────┐ │
│ │  CUPS - Sistema de Impresión        │ │
│ │  - Puerto 631                       │ │
│ │  - Gestión de colas                 │ │
│ │  - Drivers PPD                      │ │
│ └──────────┬──────────────────────────┘ │
│            │                             │
│ ┌──────────▼──────────────────────────┐ │
│ │  Samba - Compartición SMB/CIFS      │ │
│ │  - Puertos 139, 445                 │ │
│ │  - Autenticación de usuarios        │ │
│ │  - Integración con CUPS             │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

## 🚀 Instalación Rápida en Ubuntu Server

### 1. Actualizar el Sistema

```bash
sudo apt update && sudo apt upgrade -y
```

### 2. Instalar CUPS

```bash
# Instalar CUPS y herramientas
sudo apt install -y cups cups-pdf cups-client

# Habilitar e iniciar CUPS
sudo systemctl enable cups
sudo systemctl start cups

# Verificar estado
sudo systemctl status cups
```

### 3. Configurar CUPS

Editar `/etc/cups/cupsd.conf`:

```bash
sudo nano /etc/cups/cupsd.conf
```

Configuración recomendada:

```conf
# Escuchar en todas las interfaces
Listen *:631

# Permitir acceso desde la red local
<Location />
  Order allow,deny
  Allow @LOCAL
</Location>

<Location /admin>
  Order allow,deny
  Allow @LOCAL
</Location>

<Location /admin/conf>
  AuthType Default
  Require user @SYSTEM
  Order allow,deny
  Allow @LOCAL
</Location>

# Habilitar interfaz web
WebInterface Yes

# Configuración de impresión
MaxJobs 500
PreserveJobHistory Yes
PreserveJobFiles No

# Compartir impresoras localmente
Browsing On
BrowseLocalProtocols dnssd
```

Reiniciar CUPS:

```bash
sudo systemctl restart cups
```

### 4. Instalar Samba

```bash
# Instalar Samba
sudo apt install -y samba samba-common smbclient

# Habilitar e iniciar Samba
sudo systemctl enable smbd nmbd
sudo systemctl start smbd nmbd

# Verificar estado
sudo systemctl status smbd
sudo systemctl status nmbd
```

### 5. Configurar Samba

Editar `/etc/samba/smb.conf`:

```bash
sudo nano /etc/samba/smb.conf
```

Configuración recomendada:

```conf
[global]
   workgroup = WORKGROUP
   server string = Servidor de Impresion %h
   server role = standalone server
   
   # Configuración de impresión con CUPS
   printing = cups
   printcap name = cups
   load printers = yes
   
   # Seguridad
   security = user
   encrypt passwords = yes
   passdb backend = tdbsam
   
   # Logs
   log file = /var/log/samba/log.%m
   max log size = 1000
   logging = file
   
   # Red
   dns proxy = no
   interfaces = 127.0.0.0/8 eth0
   bind interfaces only = yes

# Compartir impresoras CUPS
[printers]
   comment = All Printers
   browseable = yes
   path = /var/spool/samba
   printable = yes
   guest ok = no
   read only = yes
   create mask = 0700
   valid users = @printusers

# Drivers de impresoras para Windows
[print$]
   comment = Printer Drivers
   path = /var/lib/samba/printers
   browseable = yes
   read only = yes
   guest ok = no
   write list = root, @printadmin
```

Crear directorios:

```bash
sudo mkdir -p /var/spool/samba
sudo chmod 1777 /var/spool/samba
sudo mkdir -p /var/lib/samba/printers
```

Reiniciar Samba:

```bash
sudo systemctl restart smbd nmbd
```

### 6. Instalar PostgreSQL

```bash
# Instalar PostgreSQL
sudo apt install -y postgresql postgresql-contrib

# Iniciar servicio
sudo systemctl enable postgresql
sudo systemctl start postgresql

# Crear base de datos
sudo -u postgres psql -c "CREATE DATABASE impre;"
sudo -u postgres psql -c "CREATE USER postgres WITH PASSWORD '1212';"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE impre TO postgres;"
```

### 7. Instalar Java 21 y Maven

```bash
# Instalar Java 21
sudo apt install -y openjdk-21-jdk

# Verificar instalación
java -version

# Instalar Maven
sudo apt install -y maven

# Verificar instalación
mvn -version
```

### 8. Compilar y Ejecutar la Aplicación

```bash
# Clonar o descargar el proyecto
cd /opt
sudo git clone [URL_DEL_REPOSITORIO] print-manager
cd print-manager

# Editar configuración si es necesario
sudo nano src/main/resources/application.properties

# Compilar
sudo mvn clean package -DskipTests

# Ejecutar
sudo java -jar target/iu-0.0.1-SNAPSHOT.jar
```

### 9. Configurar como Servicio Systemd

Crear archivo de servicio:

```bash
sudo nano /etc/systemd/system/print-manager.service
```

Contenido:

```ini
[Unit]
Description=Print Manager Web Application
After=network.target postgresql.service cups.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/print-manager
ExecStart=/usr/bin/java -jar /opt/print-manager/target/iu-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Habilitar e iniciar:

```bash
sudo systemctl daemon-reload
sudo systemctl enable print-manager
sudo systemctl start print-manager
sudo systemctl status print-manager
```

## 🔧 Configuración de Usuarios

### Crear Usuarios Samba

```bash
# Crear usuario del sistema
sudo useradd -M -s /usr/sbin/nologin usuario1

# Agregar a Samba
sudo smbpasswd -a usuario1
# Ingresa password cuando se solicite

# Habilitar usuario
sudo smbpasswd -e usuario1
```

### Crear Grupo de Impresión

```bash
# Crear grupo
sudo groupadd printusers

# Agregar usuarios al grupo
sudo usermod -aG printusers usuario1
```

## 🖨️ Agregar Impresoras

### Método 1: Vía Línea de Comandos

```bash
# Listar dispositivos detectados
lpinfo -v

# Agregar impresora de red (IPP)
sudo lpadmin -p NombreImpresora \
  -E \
  -v ipp://192.168.1.100/ipp/print \
  -m everywhere \
  -L "Oficina Principal" \
  -D "HP LaserJet Pro"

# Habilitar impresora
sudo cupsenable NombreImpresora
sudo cupsaccept NombreImpresora

# Configurar como predeterminada
sudo lpoptions -d NombreImpresora
```

### Método 2: Vía Interfaz Web

1. Abrir navegador: `http://ip-del-servidor:631`
2. Ir a **Administration > Add Printer**
3. Ingresar credenciales de administrador
4. Seleccionar impresora y seguir el asistente

### Método 3: Vía Aplicación Web

1. Acceder a: `http://ip-del-servidor:8080`
2. Iniciar sesión como administrador
3. Ir a **CUPS/Samba Management**
4. Click en **Import from CUPS** o **Add Printer**

## 📊 Uso del Sistema

### Acceso a la Aplicación Web

- **URL**: `http://ip-del-servidor:8080`
- **Usuario por defecto**: `admin`
- **Password por defecto**: `admin`

### Funcionalidades Principales

1. **Dashboard Principal**
   - Estado de servicios (CUPS, Samba)
   - Trabajos activos
   - Estadísticas de uso

2. **Gestión de Impresoras**
   - Agregar/editar/eliminar impresoras
   - Sincronizar con CUPS
   - Compartir vía Samba
   - Monitorear estado

3. **Gestión de Departamentos**
   - Organizar impresoras por departamento
   - Asignar computadoras
   - Control de acceso

4. **Trabajos de Impresión**
   - Ver cola de impresión
   - Cancelar trabajos
   - Historial de trabajos

5. **Usuarios**
   - Gestión de usuarios Samba
   - Permisos de impresión
   - Estadísticas por usuario

## 🔍 Comandos Útiles

### CUPS

```bash
# Ver estado de impresoras
lpstat -p -d

# Ver trabajos en cola
lpstat -o

# Cancelar trabajo
cancel [printer-name]-[job-id]

# Ver logs
sudo tail -f /var/log/cups/error_log

# Probar impresión
echo "Test de impresión" | lp -d NombreImpresora
```

### Samba

```bash
# Listar recursos compartidos
smbclient -L localhost

# Ver usuarios Samba
sudo pdbedit -L

# Ver conexiones activas
sudo smbstatus

# Probar configuración
testparm

# Ver logs
sudo tail -f /var/log/samba/log.smbd
```

### Sistema

```bash
# Ver servicios
sudo systemctl status cups
sudo systemctl status smbd
sudo systemctl status print-manager

# Reiniciar servicios
sudo systemctl restart cups
sudo systemctl restart smbd nmbd
sudo systemctl restart print-manager

# Ver logs de la aplicación
sudo journalctl -u print-manager -f
```

## 🔒 Seguridad

### Firewall (UFW)

```bash
# Habilitar UFW
sudo ufw enable

# Permitir SSH
sudo ufw allow 22/tcp

# Permitir CUPS
sudo ufw allow 631/tcp

# Permitir Samba
sudo ufw allow 139/tcp
sudo ufw allow 445/tcp

# Permitir aplicación web
sudo ufw allow 8080/tcp

# Ver reglas
sudo ufw status
```

### SSL/TLS para CUPS

```bash
# Generar certificado
sudo openssl req -new -x509 -days 365 \
  -keyout /etc/cups/ssl/server.key \
  -out /etc/cups/ssl/server.crt

# Editar cupsd.conf para forzar HTTPS
# DefaultEncryption Required
```

## 🐛 Solución de Problemas

### CUPS no inicia

```bash
# Ver logs
sudo journalctl -xe | grep cups

# Verificar configuración
sudo cupsd -t

# Reiniciar
sudo systemctl restart cups
```

### Samba no comparte impresoras

```bash
# Verificar configuración
testparm

# Ver si CUPS está corriendo
systemctl status cups

# Verificar permisos
ls -la /var/spool/samba

# Reiniciar
sudo systemctl restart smbd nmbd
```

### No se puede conectar desde clientes

```bash
# Verificar firewall
sudo ufw status

# Verificar que servicios escuchan
sudo netstat -tlnp | grep -E '631|445|139'

# Ver logs
sudo tail -f /var/log/samba/log.smbd
```

### Base de datos no conecta

```bash
# Verificar PostgreSQL
sudo systemctl status postgresql

# Verificar conexión
sudo -u postgres psql -c "SELECT version();"

# Ver logs
sudo tail -f /var/log/postgresql/postgresql-*.log
```

## 📚 Recursos Adicionales

- [Documentación CUPS](https://www.cups.org/documentation.html)
- [Documentación Samba](https://www.samba.org/samba/docs/)
- [Ubuntu Server Guide](https://ubuntu.com/server/docs)
- [Spring Boot Documentation](https://spring.io/projects/spring-boot)

## 🤝 Soporte

Para reportar problemas o solicitar ayuda:

1. Revisa los logs del sistema
2. Consulta esta documentación
3. Abre un issue en el repositorio

## 📝 Notas Importantes

1. **Permisos**: La aplicación necesita ejecutarse con permisos suficientes para modificar configuraciones de CUPS y Samba
2. **Seguridad**: Cambia las contraseñas por defecto inmediatamente
3. **Backup**: Haz respaldo de `/etc/cups/` y `/etc/samba/` antes de modificar
4. **Red**: Asegúrate de que el firewall permita el tráfico necesario
5. **Drivers**: Para impresoras Windows, instala drivers apropiados en `/var/lib/samba/printers/`

## 🔄 Actualización

```bash
cd /opt/print-manager
sudo git pull
sudo mvn clean package -DskipTests
sudo systemctl restart print-manager
```

---

**Versión**: 1.0  
**Última actualización**: 2024
