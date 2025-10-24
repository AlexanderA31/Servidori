#!/bin/bash

################################################################################
# Script de Instalación Automática
# Servidor de Impresión CUPS + Samba + Aplicación Web
# Ubuntu Server 22.04+
################################################################################

set -e

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Función para imprimir mensajes
print_msg() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_header() {
    echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
}

# Verificar que se ejecuta como root
if [[ $EUID -ne 0 ]]; then
   print_error "Este script debe ejecutarse como root (sudo)"
   exit 1
fi

# Verificar Ubuntu
if ! grep -q "Ubuntu" /etc/os-release; then
    print_warning "Este script está diseñado para Ubuntu Server"
    read -p "¿Desea continuar de todos modos? (s/N): " confirm
    if [[ ! $confirm =~ ^[Ss]$ ]]; then
        exit 1
    fi
fi

print_header "INSTALACIÓN DEL SERVIDOR DE IMPRESIÓN"

# Obtener configuración del usuario
print_msg "Configuración inicial..."
read -p "Nombre del servidor [print-server]: " SERVER_NAME
SERVER_NAME=${SERVER_NAME:-print-server}

read -p "Workgroup de Samba [WORKGROUP]: " WORKGROUP
WORKGROUP=${WORKGROUP:-WORKGROUP}

read -p "Password de PostgreSQL [1212]: " DB_PASSWORD
DB_PASSWORD=${DB_PASSWORD:-1212}

read -p "Puerto de la aplicación web [8080]: " WEB_PORT
WEB_PORT=${WEB_PORT:-8080}

print_header "1. ACTUALIZACIÓN DEL SISTEMA"
print_msg "Actualizando paquetes del sistema..."
apt update && apt upgrade -y

print_header "2. INSTALACIÓN DE CUPS"
print_msg "Instalando CUPS..."
apt install -y cups cups-pdf cups-client

print_msg "Configurando CUPS..."
# Backup de configuración original
cp /etc/cups/cupsd.conf /etc/cups/cupsd.conf.backup

# Configurar CUPS
cat > /etc/cups/cupsd.conf <<EOF
# Configuración CUPS generada por script de instalación
LogLevel warn
MaxLogSize 0
PageLogFormat
Listen *:631
Browsing On
BrowseLocalProtocols dnssd
DefaultAuthType Basic
WebInterface Yes

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

<Policy default>
  JobPrivateAccess default
  JobPrivateValues default
  SubscriptionPrivateAccess default
  SubscriptionPrivateValues default
  <Limit All>
    Order deny,allow
  </Limit>
  <Limit CUPS-Get-Document>
    AuthType Default
    Require user @OWNER @SYSTEM
    Order deny,allow
  </Limit>
</Policy>

<Policy authenticated>
  JobPrivateAccess default
  JobPrivateValues default
  SubscriptionPrivateAccess default
  SubscriptionPrivateValues default
  <Limit All>
    AuthType Default
    Order deny,allow
  </Limit>
</Policy>

MaxJobs 500
PreserveJobHistory Yes
PreserveJobFiles No
EOF

print_msg "Iniciando CUPS..."
systemctl enable cups
systemctl restart cups

# Verificar estado
if systemctl is-active --quiet cups; then
    print_msg "✓ CUPS instalado y ejecutándose correctamente"
else
    print_error "✗ CUPS no está ejecutándose"
    exit 1
fi

print_header "3. INSTALACIÓN DE SAMBA"
print_msg "Instalando Samba..."
apt install -y samba samba-common smbclient

print_msg "Configurando Samba..."
# Backup de configuración original
cp /etc/samba/smb.conf /etc/samba/smb.conf.backup

# Configurar Samba
cat > /etc/samba/smb.conf <<EOF
[global]
   workgroup = $WORKGROUP
   server string = $SERVER_NAME
   server role = standalone server
   
   # Integración con CUPS
   printing = cups
   printcap name = cups
   load printers = yes
   
   # Seguridad
   security = user
   encrypt passwords = yes
   passdb backend = tdbsam
   map to guest = bad user
   
   # Logs
   log file = /var/log/samba/log.%m
   max log size = 1000
   logging = file
   log level = 1
   
   # Red
   dns proxy = no
   bind interfaces only = no

# Todas las impresoras CUPS
[printers]
   comment = All Printers
   browseable = yes
   path = /var/spool/samba
   printable = yes
   guest ok = no
   read only = yes
   create mask = 0700

# Drivers de impresoras
[print\$]
   comment = Printer Drivers
   path = /var/lib/samba/printers
   browseable = yes
   read only = yes
   guest ok = no
   write list = root
EOF

# Crear directorios necesarios
print_msg "Creando directorios..."
mkdir -p /var/spool/samba
chmod 1777 /var/spool/samba
mkdir -p /var/lib/samba/printers
chown root:root /var/lib/samba/printers
chmod 755 /var/lib/samba/printers

print_msg "Iniciando Samba..."
systemctl enable smbd nmbd
systemctl restart smbd nmbd

# Verificar estado
if systemctl is-active --quiet smbd && systemctl is-active --quiet nmbd; then
    print_msg "✓ Samba instalado y ejecutándose correctamente"
else
    print_error "✗ Samba no está ejecutándose correctamente"
    exit 1
fi

print_header "4. INSTALACIÓN DE POSTGRESQL"
print_msg "Instalando PostgreSQL..."
apt install -y postgresql postgresql-contrib

print_msg "Configurando base de datos..."
sudo -u postgres psql <<EOF
CREATE DATABASE impre;
CREATE USER postgres WITH PASSWORD '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON DATABASE impre TO postgres;
\q
EOF

# Verificar estado
if systemctl is-active --quiet postgresql; then
    print_msg "✓ PostgreSQL instalado y ejecutándose correctamente"
else
    print_error "✗ PostgreSQL no está ejecutándose"
    exit 1
fi

print_header "5. INSTALACIÓN DE JAVA 21 Y MAVEN"
print_msg "Instalando OpenJDK 21..."
apt install -y openjdk-21-jdk

print_msg "Instalando Maven..."
apt install -y maven

# Verificar instalación
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -ge "21" ]; then
    print_msg "✓ Java $JAVA_VERSION instalado correctamente"
else
    print_error "✗ Error en instalación de Java"
    exit 1
fi

print_header "6. CONFIGURACIÓN DEL FIREWALL"
print_msg "Configurando UFW..."

# Instalar UFW si no está instalado
apt install -y ufw

# Configurar reglas
ufw --force enable
ufw allow 22/tcp comment 'SSH'
ufw allow 631/tcp comment 'CUPS'
ufw allow 139/tcp comment 'Samba NetBIOS'
ufw allow 445/tcp comment 'Samba SMB'
ufw allow $WEB_PORT/tcp comment 'Aplicación Web'

print_msg "✓ Firewall configurado"

print_header "7. CONFIGURACIÓN DE LA APLICACIÓN WEB"

# Actualizar application.properties si existe
if [ -f "src/main/resources/application.properties" ]; then
    print_msg "Actualizando configuración de la aplicación..."
    sed -i "s/server.port=.*/server.port=$WEB_PORT/" src/main/resources/application.properties
    sed -i "s/spring.datasource.password=.*/spring.datasource.password=$DB_PASSWORD/" src/main/resources/application.properties
fi

print_msg "Compilando aplicación..."
mvn clean package -DskipTests

if [ -f "target/iu-0.0.1-SNAPSHOT.jar" ]; then
    print_msg "✓ Aplicación compilada exitosamente"
else
    print_error "✗ Error al compilar la aplicación"
    exit 1
fi

print_header "8. CONFIGURACIÓN COMO SERVICIO SYSTEMD"

print_msg "Creando servicio systemd..."
cat > /etc/systemd/system/print-manager.service <<EOF
[Unit]
Description=Print Manager Web Application
After=network.target postgresql.service cups.service smbd.service

[Service]
Type=simple
User=root
WorkingDirectory=$(pwd)
ExecStart=/usr/bin/java -jar $(pwd)/target/iu-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

print_msg "Habilitando e iniciando servicio..."
systemctl daemon-reload
systemctl enable print-manager
systemctl start print-manager

# Esperar a que inicie
sleep 5

if systemctl is-active --quiet print-manager; then
    print_msg "✓ Aplicación web instalada y ejecutándose"
else
    print_warning "⚠ La aplicación no está ejecutándose. Verifique los logs con: journalctl -u print-manager -n 50"
fi

print_header "INSTALACIÓN COMPLETADA"

# Obtener IP del servidor
SERVER_IP=$(hostname -I | awk '{print $1}')

echo ""
print_msg "═══════════════════════════════════════════════════════"
print_msg "           RESUMEN DE LA INSTALACIÓN"
print_msg "═══════════════════════════════════════════════════════"
echo ""
echo -e "  ${GREEN}✓ CUPS${NC} instalado y ejecutándose"
echo -e "    URL: ${BLUE}http://$SERVER_IP:631${NC}"
echo ""
echo -e "  ${GREEN}✓ Samba${NC} instalado y ejecutándose"
echo -e "    Workgroup: ${BLUE}$WORKGROUP${NC}"
echo ""
echo -e "  ${GREEN}✓ PostgreSQL${NC} instalado y ejecutándose"
echo -e "    Base de datos: ${BLUE}impre${NC}"
echo ""
echo -e "  ${GREEN}✓ Aplicación Web${NC} instalada"
echo -e "    URL: ${BLUE}http://$SERVER_IP:$WEB_PORT${NC}"
echo -e "    Usuario: ${BLUE}admin${NC}"
echo -e "    Password: ${BLUE}admin${NC}"
echo ""
print_msg "═══════════════════════════════════════════════════════"
echo ""
print_warning "IMPORTANTE: Cambia la contraseña de admin inmediatamente"
echo ""
print_msg "Comandos útiles:"
echo "  • Ver estado de servicios:"
echo "    sudo systemctl status cups smbd print-manager"
echo ""
echo "  • Ver logs de la aplicación:"
echo "    sudo journalctl -u print-manager -f"
echo ""
echo "  • Reiniciar servicios:"
echo "    sudo systemctl restart cups smbd nmbd print-manager"
echo ""
echo "  • Agregar usuario Samba:"
echo "    sudo smbpasswd -a NOMBRE_USUARIO"
echo ""
print_msg "Para más información, consulta: CUPS-SAMBA-SETUP.md"
echo ""

# Guardar información en archivo
cat > /root/print-server-info.txt <<EOF
===================================================
INFORMACIÓN DEL SERVIDOR DE IMPRESIÓN
===================================================
Fecha de instalación: $(date)
Servidor: $SERVER_NAME
IP: $SERVER_IP

SERVICIOS:
- CUPS: http://$SERVER_IP:631
- Aplicación Web: http://$SERVER_IP:$WEB_PORT
- Samba Workgroup: $WORKGROUP

CREDENCIALES:
- Usuario web: admin / admin (CAMBIAR INMEDIATAMENTE)
- PostgreSQL: postgres / $DB_PASSWORD

ARCHIVOS DE CONFIGURACIÓN:
- CUPS: /etc/cups/cupsd.conf
- Samba: /etc/samba/smb.conf
- PostgreSQL: /etc/postgresql/*/main/pg_hba.conf
- Aplicación: $(pwd)/src/main/resources/application.properties

LOGS:
- CUPS: /var/log/cups/error_log
- Samba: /var/log/samba/log.smbd
- Aplicación: journalctl -u print-manager

COMANDOS ÚTILES:
- Estado: sudo systemctl status cups smbd print-manager
- Reiniciar: sudo systemctl restart cups smbd nmbd print-manager
- Logs: sudo journalctl -u print-manager -f
===================================================
EOF

print_msg "Información guardada en: /root/print-server-info.txt"
echo ""
