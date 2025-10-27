#!/bin/bash

################################################################################
# INSTALADOR AUTOMÁTICO - SISTEMA DE GESTIÓN DE IMPRESORAS
# Sistema de impresión empresarial con CUPS + Samba + Spring Boot
# Compatible con: Ubuntu Server 22.04+ LTS
# 
# Servidor destino:
# - Usuario: tics
# - IP: 10.1.16.31
# - Puerto: 22
################################################################################

set -e  # Detener en caso de error

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # Sin color

# Configuración
APP_NAME="print-manager"
APP_USER="printmgr"
APP_DIR="/opt/print-manager"
DB_NAME="impre"
DB_USER="postgres"
DB_PASS="PrintMgr2025!"
APP_PORT="8080"
JAVA_VERSION="21"

################################################################################
# FUNCIONES AUXILIARES
################################################################################

print_header() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_info() {
    echo -e "${CYAN}ℹ${NC} $1"
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        print_error "Este script debe ejecutarse como root (sudo)"
        exit 1
    fi
}

################################################################################
# FUNCIÓN PRINCIPAL DE INSTALACIÓN
################################################################################

install_system() {
    print_header "INICIANDO INSTALACIÓN DEL SISTEMA DE GESTIÓN DE IMPRESORAS"
    
    # 1. Verificar sistema operativo
    print_info "Verificando sistema operativo..."
    if grep -q "Ubuntu" /etc/os-release; then
        VERSION=$(grep "VERSION_ID" /etc/os-release | cut -d'"' -f2)
        print_success "Ubuntu $VERSION detectado"
    else
        print_warning "Este script está optimizado para Ubuntu Server 22.04+"
        read -p "¿Desea continuar de todos modos? (s/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Ss]$ ]]; then
            exit 1
        fi
    fi
    
    # 2. Actualizar sistema
    print_header "ACTUALIZANDO SISTEMA"
    print_info "Actualizando repositorios..."
    apt-get update -qq > /dev/null 2>&1
    print_info "Actualizando paquetes instalados..."
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -qq > /dev/null 2>&1
    print_success "Sistema actualizado"
    
    # 3. Instalar Java 21
    print_header "INSTALANDO JAVA 21"
    if java -version 2>&1 | grep -q "openjdk version \"21"; then
        print_success "Java 21 ya está instalado"
    else
        print_info "Descargando e instalando Java 21..."
        apt-get install -y -qq openjdk-21-jdk openjdk-21-jre > /dev/null 2>&1
        print_success "Java 21 instalado"
    fi
    JAVA_VER=$(java -version 2>&1 | head -n 1)
    print_info "Versión: $JAVA_VER"
    
    # 4. Instalar PostgreSQL
    print_header "INSTALANDO POSTGRESQL"
    if systemctl is-active --quiet postgresql 2>/dev/null; then
        print_success "PostgreSQL ya está instalado y activo"
    else
        print_info "Instalando PostgreSQL..."
        apt-get install -y -qq postgresql postgresql-contrib > /dev/null 2>&1
        systemctl enable postgresql > /dev/null 2>&1
        systemctl start postgresql
        print_success "PostgreSQL instalado y activo"
    fi
    PG_VER=$(sudo -u postgres psql --version | awk '{print $3}')
    print_info "Versión: PostgreSQL $PG_VER"
    
    # 5. Configurar base de datos
    print_header "CONFIGURANDO BASE DE DATOS"
    print_info "Creando base de datos '$DB_NAME'..."
    
    # Crear base de datos si no existe
    if sudo -u postgres psql -lqt | cut -d \| -f 1 | grep -qw "$DB_NAME"; then
        print_info "Base de datos '$DB_NAME' ya existe"
    else
        sudo -u postgres psql -c "CREATE DATABASE $DB_NAME;" > /dev/null 2>&1
        print_success "Base de datos '$DB_NAME' creada"
    fi
    
    # Configurar contraseña
    sudo -u postgres psql -c "ALTER USER $DB_USER WITH PASSWORD '$DB_PASS';" > /dev/null 2>&1
    print_success "Base de datos configurada"
    
    # 6. Instalar CUPS
    print_header "INSTALANDO CUPS (SISTEMA DE IMPRESIÓN)"
    print_info "Instalando CUPS..."
    apt-get install -y -qq cups cups-client cups-filters > /dev/null 2>&1
    systemctl enable cups > /dev/null 2>&1
    systemctl start cups
    
    # Configurar CUPS para acceso remoto
    print_info "Configurando acceso remoto a CUPS..."
    cp /etc/cups/cupsd.conf /etc/cups/cupsd.conf.backup
    
    # Permitir acceso remoto
    sed -i 's/^Listen localhost:631/Listen 631/' /etc/cups/cupsd.conf
    
    # Permitir acceso desde cualquier IP
    sed -i '/<Location \/>/,/<\/Location>/ s/Order allow,deny/Order deny,allow/' /etc/cups/cupsd.conf
    sed -i '/<Location \/>/,/<\/Location>/ s/Allow localhost/Allow all/' /etc/cups/cupsd.conf
    
    # Permitir administración remota
    sed -i '/<Location \/admin>/,/<\/Location>/ s/Order allow,deny/Order deny,allow/' /etc/cups/cupsd.conf
    sed -i '/<Location \/admin>/,/<\/Location>/ s/Allow localhost/Allow all/' /etc/cups/cupsd.conf
    
    systemctl restart cups
    CUPS_VER=$(cups-config --version)
    print_success "CUPS $CUPS_VER instalado y configurado"
    
    # 7. Instalar Samba
    print_header "INSTALANDO SAMBA (COMPARTICIÓN DE IMPRESORAS)"
    print_info "Instalando Samba..."
    apt-get install -y -qq samba samba-common-bin > /dev/null 2>&1
    systemctl enable smbd nmbd > /dev/null 2>&1
    
    # Backup de configuración original
    if [ -f /etc/samba/smb.conf ]; then
        cp /etc/samba/smb.conf /etc/samba/smb.conf.backup
    fi
    
    # Configurar Samba
    print_info "Configurando Samba..."
    cat > /etc/samba/smb.conf <<'EOF'
[global]
   workgroup = WORKGROUP
   server string = Print Server Ubuntu
   netbios name = PRINTSERVER
   security = user
   map to guest = bad user
   dns proxy = no
   
   # Configuración de impresoras
   load printers = yes
   printing = cups
   printcap name = cups
   
   # Logs
   log file = /var/log/samba/log.%m
   max log size = 50
   log level = 1
   
   # Rendimiento
   socket options = TCP_NODELAY IPTOS_LOWDELAY
   
   # Permitir navegación
   local master = yes
   preferred master = yes
   
[printers]
   comment = Todas las Impresoras
   path = /var/spool/samba
   browseable = yes
   guest ok = yes
   writable = no
   printable = yes
   create mode = 0700
   use client driver = yes

[print$]
   comment = Drivers de Impresoras
   path = /var/lib/samba/printers
   browseable = yes
   guest ok = no
   read only = yes
   write list = root
EOF
    
    # Crear directorios necesarios
    mkdir -p /var/spool/samba
    chmod 1777 /var/spool/samba
    mkdir -p /var/lib/samba/printers
    chmod 755 /var/lib/samba/printers
    
    systemctl restart smbd nmbd
    SAMBA_VER=$(smbd --version | awk '{print $2}')
    print_success "Samba $SAMBA_VER instalado y configurado"
    
    # 8. Instalar Maven
    print_header "INSTALANDO MAVEN"
    if command -v mvn &> /dev/null; then
        MVN_VER=$(mvn -version | head -n 1 | awk '{print $3}')
        print_success "Maven $MVN_VER ya está instalado"
    else
        print_info "Instalando Maven..."
        apt-get install -y -qq maven > /dev/null 2>&1
        MVN_VER=$(mvn -version | head -n 1 | awk '{print $3}')
        print_success "Maven $MVN_VER instalado"
    fi
    
    # 9. Crear usuario de aplicación
    print_header "CREANDO USUARIO DE APLICACIÓN"
    if id "$APP_USER" &>/dev/null; then
        print_success "Usuario $APP_USER ya existe"
    else
        useradd -r -s /bin/false -d "$APP_DIR" -m "$APP_USER"
        print_success "Usuario $APP_USER creado"
    fi
    
    # 10. Copiar aplicación
    print_header "COPIANDO ARCHIVOS DE APLICACIÓN"
    print_info "Copiando archivos al directorio $APP_DIR..."
    
    CURRENT_DIR=$(pwd)
    
    # Crear directorio si no existe
    mkdir -p "$APP_DIR"
    
    # Copiar archivos (excluyendo target y cache)
    rsync -a --exclude='target' --exclude='.git' --exclude='*.log' \
          "$CURRENT_DIR/" "$APP_DIR/" 2>/dev/null || \
    cp -r "$CURRENT_DIR"/* "$APP_DIR/" 2>/dev/null
    
    # Crear directorio de datos
    mkdir -p "$APP_DIR/data/uploads"
    
    # Ajustar permisos
    chown -R "$APP_USER:$APP_USER" "$APP_DIR"
    chmod -R 755 "$APP_DIR"
    
    print_success "Archivos copiados a $APP_DIR"
    
    # 11. Configurar application.properties
    print_header "CONFIGURANDO APLICACIÓN"
    print_info "Generando archivo de configuración..."
    
    cat > "$APP_DIR/src/main/resources/application.properties" <<EOF
# Configuración generada automáticamente
# Fecha: $(date '+%Y-%m-%d %H:%M:%S')

# Puerto de la aplicación
server.port=$APP_PORT

# PostgreSQL
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.url=jdbc:postgresql://localhost:5432/$DB_NAME
spring.datasource.username=$DB_USER
spring.datasource.password=$DB_PASS

# Hibernate
spring.jpa.hibernate.ddl-auto=update
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.show-sql=false
spring.jpa.defer-datasource-initialization=true
spring.sql.init.mode=always

# Logging
logging.level.root=info
logging.level.es.ucm.fdi.iu=info
logging.level.org.hibernate.SQL=info

# Ruta de archivos
es.ucm.fdi.base-path=$APP_DIR/data/uploads

# Samba
samba.guest.access=yes
samba.browseable=yes

# Cache desactivado en producción
spring.thymeleaf.cache=true
spring.resources.chain.cache=true
EOF
    
    chown "$APP_USER:$APP_USER" "$APP_DIR/src/main/resources/application.properties"
    print_success "Configuración creada"
    
    # 12. Compilar aplicación
    print_header "COMPILANDO APLICACIÓN"
    print_info "Este proceso puede tomar varios minutos..."
    print_info "Descargando dependencias y compilando código..."
    
    cd "$APP_DIR"
    
    # Compilar como usuario de aplicación
    if sudo -u "$APP_USER" mvn clean package -DskipTests -q; then
        print_success "Aplicación compilada exitosamente"
        
        # Verificar que el JAR existe
        if [ -f "$APP_DIR/target/iu-0.0.1-SNAPSHOT.jar" ]; then
            JAR_SIZE=$(du -h "$APP_DIR/target/iu-0.0.1-SNAPSHOT.jar" | cut -f1)
            print_info "JAR generado: iu-0.0.1-SNAPSHOT.jar ($JAR_SIZE)"
        else
            print_error "El archivo JAR no fue generado"
            exit 1
        fi
    else
        print_error "Error al compilar la aplicación"
        print_error "Revise los logs de Maven para más detalles"
        exit 1
    fi
    
    # 13. Crear servicio systemd
    print_header "CREANDO SERVICIO SYSTEMD"
    print_info "Configurando servicio de inicio automático..."
    
    cat > /etc/systemd/system/print-manager.service <<EOF
[Unit]
Description=Print Manager - Sistema de Gestión de Impresoras
Documentation=https://github.com/tu-usuario/print-manager
After=network.target postgresql.service cups.service
Wants=postgresql.service cups.service

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_DIR

# Comando de inicio
ExecStart=/usr/bin/java -jar $APP_DIR/target/iu-0.0.1-SNAPSHOT.jar

# Reinicio automático
Restart=on-failure
RestartSec=10

# Límites de recursos
MemoryLimit=2G
CPUQuota=200%

# Logs
StandardOutput=journal
StandardError=journal
SyslogIdentifier=print-manager

[Install]
WantedBy=multi-user.target
EOF
    
    systemctl daemon-reload
    systemctl enable print-manager > /dev/null 2>&1
    print_success "Servicio systemd creado y habilitado"
    
    # 14. Configurar firewall
    print_header "CONFIGURANDO FIREWALL"
    if command -v ufw &> /dev/null; then
        print_info "Configurando UFW..."
        
        # Permitir puertos necesarios
        ufw allow $APP_PORT/tcp comment 'Print Manager Web' > /dev/null 2>&1
        ufw allow 631/tcp comment 'CUPS IPP' > /dev/null 2>&1
        ufw allow 139/tcp comment 'Samba NetBIOS' > /dev/null 2>&1
        ufw allow 445/tcp comment 'Samba SMB' > /dev/null 2>&1
        ufw allow 22/tcp comment 'SSH' > /dev/null 2>&1
        
        # Habilitar UFW si no está activo
        if ! ufw status | grep -q "Status: active"; then
            print_warning "UFW no está activo. Para activarlo ejecute: sudo ufw enable"
        else
            print_success "Firewall configurado"
        fi
    else
        print_warning "UFW no está instalado"
        print_info "Instalando UFW..."
        apt-get install -y -qq ufw > /dev/null 2>&1
        ufw allow $APP_PORT/tcp > /dev/null 2>&1
        ufw allow 631/tcp > /dev/null 2>&1
        ufw allow 139/tcp > /dev/null 2>&1
        ufw allow 445/tcp > /dev/null 2>&1
        ufw allow 22/tcp > /dev/null 2>&1
        print_success "UFW instalado y configurado"
    fi
    
    # 15. Inicializar base de datos
    print_header "INICIALIZANDO BASE DE DATOS"
    if [ -f "$APP_DIR/create-tables.sql" ]; then
        print_info "Ejecutando script de creación de tablas..."
        sudo -u postgres psql -d "$DB_NAME" -f "$APP_DIR/create-tables.sql" > /dev/null 2>&1 || true
        print_success "Script SQL ejecutado"
    else
        print_info "No se encontró create-tables.sql, se usará auto-DDL de Hibernate"
    fi
    
    # 16. Iniciar servicio
    print_header "INICIANDO SERVICIO"
    print_info "Iniciando Print Manager..."
    
    systemctl start print-manager
    
    # Esperar a que el servicio inicie
    print_info "Esperando inicio del servicio..."
    sleep 3
    
    # Verificar hasta 30 segundos
    for i in {1..10}; do
        if systemctl is-active --quiet print-manager; then
            print_success "Servicio iniciado correctamente"
            break
        fi
        if [ $i -eq 10 ]; then
            print_error "El servicio no pudo iniciarse"
            print_info "Mostrando últimas 30 líneas del log:"
            journalctl -u print-manager -n 30 --no-pager
            exit 1
        fi
        sleep 3
    done
    
    # Verificar que la aplicación responde
    sleep 5
    if curl -s http://localhost:$APP_PORT > /dev/null 2>&1; then
        print_success "Aplicación web respondiendo correctamente"
    else
        print_warning "La aplicación puede estar iniciando aún..."
    fi
}

################################################################################
# FUNCIÓN DE VERIFICACIÓN POST-INSTALACIÓN
################################################################################

verify_installation() {
    print_header "VERIFICANDO INSTALACIÓN"
    
    echo ""
    print_info "Estado de servicios:"
    echo ""
    
    # PostgreSQL
    if systemctl is-active --quiet postgresql; then
        print_success "PostgreSQL: ✓ Activo"
    else
        print_error "PostgreSQL: ✗ Inactivo"
    fi
    
    # CUPS
    if systemctl is-active --quiet cups; then
        print_success "CUPS: ✓ Activo"
    else
        print_error "CUPS: ✗ Inactivo"
    fi
    
    # Samba
    if systemctl is-active --quiet smbd; then
        print_success "Samba (smbd): ✓ Activo"
    else
        print_error "Samba (smbd): ✗ Inactivo"
    fi
    
    if systemctl is-active --quiet nmbd; then
        print_success "Samba (nmbd): ✓ Activo"
    else
        print_error "Samba (nmbd): ✗ Inactivo"
    fi
    
    # Print Manager
    if systemctl is-active --quiet print-manager; then
        print_success "Print Manager: ✓ Activo"
    else
        print_error "Print Manager: ✗ Inactivo"
    fi
    
    echo ""
    print_info "Puertos en escucha:"
    echo ""
    
    if netstat -tlnp 2>/dev/null | grep -q ":$APP_PORT"; then
        print_success "Puerto $APP_PORT (Web): ✓ Abierto"
    else
        print_warning "Puerto $APP_PORT (Web): ⚠ No detectado"
    fi
    
    if netstat -tlnp 2>/dev/null | grep -q ":631"; then
        print_success "Puerto 631 (CUPS): ✓ Abierto"
    else
        print_warning "Puerto 631 (CUPS): ⚠ No detectado"
    fi
    
    if netstat -tlnp 2>/dev/null | grep -q ":445"; then
        print_success "Puerto 445 (Samba): ✓ Abierto"
    else
        print_warning "Puerto 445 (Samba): ⚠ No detectado"
    fi
}

################################################################################
# FUNCIÓN DE INFORMACIÓN FINAL
################################################################################

print_final_info() {
    IP_ADDRESS=$(hostname -I | awk '{print $1}')
    HOSTNAME=$(hostname)
    
    print_header "INSTALACIÓN COMPLETADA EXITOSAMENTE"
    
    cat <<EOF

${GREEN}╔═══════════════════════════════════════════════════════════════════════╗
║                                                                       ║
║              🎉 INSTALACIÓN COMPLETADA EXITOSAMENTE 🎉                ║
║                                                                       ║
╚═══════════════════════════════════════════════════════════════════════╝${NC}

${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${BLUE}📱 INFORMACIÓN DE ACCESO${NC}
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

${GREEN}🌐 Interfaz Web Principal:${NC}
   URL:              http://$IP_ADDRESS:$APP_PORT
   URL alternativa:  http://$HOSTNAME:$APP_PORT
   
   ${CYAN}Credenciales de Acceso:${NC}
   👤 Usuario:       admin
   🔐 Contraseña:    admin
   
   ${YELLOW}⚠️  IMPORTANTE: Cambia la contraseña inmediatamente después del primer login${NC}

${GREEN}🖨️  CUPS (Sistema de Impresión):${NC}
   Web Interface:    http://$IP_ADDRESS:631
   
${GREEN}📁 Samba (Compartición de Red):${NC}
   Servidor:         \\\\$IP_ADDRESS
   Impresoras:       \\\\$IP_ADDRESS\\printers
   
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${BLUE}🔧 GESTIÓN DEL SISTEMA${NC}
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

${CYAN}Ver estado de servicios:${NC}
   sudo systemctl status print-manager
   sudo systemctl status cups
   sudo systemctl status smbd

${CYAN}Ver logs en tiempo real:${NC}
   sudo journalctl -u print-manager -f
   sudo tail -f /var/log/cups/error_log
   sudo tail -f /var/log/samba/log.smbd

${CYAN}Reiniciar servicios:${NC}
   sudo systemctl restart print-manager
   sudo systemctl restart cups
   sudo systemctl restart smbd nmbd

${CYAN}Detener servicios:${NC}
   sudo systemctl stop print-manager

${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${BLUE}📁 UBICACIÓN DE ARCHIVOS${NC}
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

Directorio aplicación:     $APP_DIR
Configuración:             $APP_DIR/src/main/resources/application.properties
JAR ejecutable:            $APP_DIR/target/iu-0.0.1-SNAPSHOT.jar
Servicio systemd:          /etc/systemd/system/print-manager.service
Datos y uploads:           $APP_DIR/data/uploads
Logs de aplicación:        sudo journalctl -u print-manager

${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${BLUE}🗄️  BASE DE DATOS${NC}
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

Motor:                     PostgreSQL
Base de datos:             $DB_NAME
Usuario:                   $DB_USER
Host:                      localhost:5432

${CYAN}Conectarse a la base de datos:${NC}
   sudo -u postgres psql -d $DB_NAME

${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${BLUE}🔥 FIREWALL${NC}
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

Puertos abiertos:
   • $APP_PORT/tcp - Aplicación Web
   • 631/tcp - CUPS (IPP)
   • 139/tcp - Samba (NetBIOS)
   • 445/tcp - Samba (SMB)
   • 22/tcp - SSH

${CYAN}Ver estado del firewall:${NC}
   sudo ufw status

${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${BLUE}📖 PRÓXIMOS PASOS${NC}
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

1. ${GREEN}Accede a la interfaz web:${NC} http://$IP_ADDRESS:$APP_PORT
2. ${GREEN}Cambia la contraseña de admin${NC}
3. ${GREEN}Configura departamentos${NC} en la sección Departments
4. ${GREEN}Descubre impresoras en red${NC} en Network Management
5. ${GREEN}Agrega impresoras manualmente${NC} si es necesario
6. ${GREEN}Comparte impresoras vía Samba${NC} desde CUPS/Samba Management

${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}
${BLUE}📚 DOCUMENTACIÓN${NC}
${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

README:                    $APP_DIR/README.md
API REST:                  http://$IP_ADDRESS:$APP_PORT/api/

${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}

${GREEN}✨ ¡El sistema está listo para usar! ✨${NC}

${CYAN}Para soporte o reportar problemas:${NC}
   • Revisa los logs: sudo journalctl -u print-manager
   • Verifica el README.md en $APP_DIR
   
EOF
}

################################################################################
# FUNCIÓN PARA CREAR SCRIPT DE DESINSTALACIÓN
################################################################################

create_uninstall_script() {
    cat > /usr/local/bin/uninstall-print-manager.sh <<'EOFUNINSTALL'
#!/bin/bash
# Script de desinstalación

echo "Desinstalando Print Manager..."

# Detener servicios
systemctl stop print-manager
systemctl disable print-manager

# Eliminar servicio
rm -f /etc/systemd/system/print-manager.service
systemctl daemon-reload

# Eliminar aplicación
rm -rf /opt/print-manager

# Eliminar usuario
userdel -r printmgr 2>/dev/null

# Opcional: desinstalar dependencias
read -p "¿Desinstalar PostgreSQL, CUPS y Samba? (s/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Ss]$ ]]; then
    apt-get remove -y postgresql cups samba
    apt-get autoremove -y
fi

echo "Desinstalación completada"
EOFUNINSTALL

    chmod +x /usr/local/bin/uninstall-print-manager.sh
    print_info "Script de desinstalación creado: /usr/local/bin/uninstall-print-manager.sh"
}

################################################################################
# FUNCIÓN PRINCIPAL
################################################################################

main() {
    clear
    check_root
    
    # Banner
    cat <<'EOF'

╔═══════════════════════════════════════════════════════════════════════╗
║                                                                       ║
║         🖨️  INSTALADOR DE SISTEMA DE GESTIÓN DE IMPRESORAS           ║
║                                                                       ║
║                    CUPS + Samba + Spring Boot                        ║
║                         Ubuntu Server 22.04+                          ║
║                                                                       ║
╚═══════════════════════════════════════════════════════════════════════╝

EOF
    
    echo ""
    print_warning "Este script instalará y configurará los siguientes componentes:"
    echo ""
    echo "  ✓ Java 21 OpenJDK"
    echo "  ✓ PostgreSQL (Base de datos)"
    echo "  ✓ CUPS (Sistema de impresión Unix/Linux)"
    echo "  ✓ Samba (Compartición de impresoras en red)"
    echo "  ✓ Maven (Herramienta de construcción)"
    echo "  ✓ Sistema de gestión web (Spring Boot)"
    echo ""
    print_info "La instalación puede tomar entre 5-15 minutos dependiendo de la conexión"
    echo ""
    
    read -p "¿Desea continuar con la instalación? (s/N): " -n 1 -r
    echo
    echo
    
    if [[ ! $REPLY =~ ^[Ss]$ ]]; then
        print_info "Instalación cancelada por el usuario"
        exit 0
    fi
    
    # Registrar inicio
    START_TIME=$(date +%s)
    
    # Ejecutar instalación
    install_system
    
    # Crear script de desinstalación
    create_uninstall_script
    
    # Verificar instalación
    verify_installation
    
    # Calcular tiempo transcurrido
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    MINUTES=$((DURATION / 60))
    SECONDS=$((DURATION % 60))
    
    # Mostrar información final
    print_final_info
    
    echo ""
    print_success "Tiempo total de instalación: ${MINUTES}m ${SECONDS}s"
    echo ""
}

# Ejecutar instalación
main "$@"