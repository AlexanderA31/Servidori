cd ~/Servidori

# Crear versión corregida del script
cat > install-ubuntu-fixed.sh << 'ENDOFSCRIPT'
#!/bin/bash

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Configuración
APP_USER="printmgr"
APP_DIR="/opt/print-manager"
DB_NAME="impre"
DB_USER="postgres"
DB_PASS="PrintMgr2025!"
APP_PORT="8080"

print_header() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

print_success() { echo -e "${GREEN}✓${NC} $1"; }
print_error() { echo -e "${RED}✗${NC} $1"; }
print_info() { echo -e "${CYAN}ℹ${NC} $1"; }

check_root() {
    if [[ $EUID -ne 0 ]]; then
        print_error "Este script debe ejecutarse como root (sudo)"
        exit 1
    fi
}

install_system() {
    print_header "INSTALACIÓN - SISTEMA DE GESTIÓN DE IMPRESORAS"
    
    # Actualizar
    print_header "1/9 - ACTUALIZANDO SISTEMA"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -qq
    print_success "Sistema actualizado"
    
    # Java
    print_header "2/9 - INSTALANDO JAVA 21"
    if java -version 2>&1 | grep -q "21"; then
        print_success "Java 21 ya instalado"
    else
        apt-get install -y openjdk-21-jdk openjdk-21-jre
        print_success "Java 21 instalado"
    fi
    
    # PostgreSQL
    print_header "3/9 - INSTALANDO POSTGRESQL"
    if systemctl is-active --quiet postgresql 2>/dev/null; then
        print_success "PostgreSQL activo"
    else
        apt-get install -y postgresql postgresql-contrib
        systemctl enable postgresql
        systemctl start postgresql
        print_success "PostgreSQL instalado"
    fi
    
    # Configurar BD
    print_header "4/9 - CONFIGURANDO BASE DE DATOS"
    sudo -u postgres psql -lqt | cut -d \| -f 1 | grep -qw "$DB_NAME" || \
        sudo -u postgres psql -c "CREATE DATABASE $DB_NAME;"
    sudo -u postgres psql -c "ALTER USER $DB_USER WITH PASSWORD '$DB_PASS';"
    print_success "BD: $DB_NAME configurada"
    
    # CUPS
    print_header "5/9 - INSTALANDO CUPS"
    apt-get install -y cups cups-client cups-filters cups-daemon libcups2-dev
    systemctl enable cups
    systemctl start cups
    
    # Configurar CUPS (sin usar cups-config)
    if [ -f /etc/cups/cupsd.conf ]; then
        cp /etc/cups/cupsd.conf /etc/cups/cupsd.conf.backup
        sed -i 's/^Listen localhost:631/Listen 631/' /etc/cups/cupsd.conf
        sed -i '/<Location \/>/,/<\/Location>/ s/Order allow,deny/Order deny,allow/' /etc/cups/cupsd.conf
        sed -i '/<Location \/>/,/<\/Location>/ s/Allow localhost/Allow all/' /etc/cups/cupsd.conf
        systemctl restart cups
    fi
    
    CUPS_VERSION=$(dpkg -l | grep " cups " | awk '{print $3}')
    print_success "CUPS $CUPS_VERSION instalado"
    
    # Samba
    print_header "6/9 - INSTALANDO SAMBA"
    apt-get install -y samba samba-common-bin
    systemctl enable smbd nmbd
    
    [ -f /etc/samba/smb.conf ] && cp /etc/samba/smb.conf /etc/samba/smb.conf.backup
    
    cat > /etc/samba/smb.conf << 'EOFSMB'
[global]
   workgroup = WORKGROUP
   server string = Print Server
   netbios name = PRINTSERVER
   security = user
   map to guest = bad user
   dns proxy = no
   load printers = yes
   printing = cups
   printcap name = cups
   log file = /var/log/samba/log.%m
   max log size = 50
   
[printers]
   comment = Todas las Impresoras
   path = /var/spool/samba
   browseable = yes
   guest ok = yes
   writable = no
   printable = yes
   create mode = 0700

[print$]
   comment = Drivers
   path = /var/lib/samba/printers
   browseable = yes
   guest ok = no
   read only = yes
   write list = root
EOFSMB
    
    mkdir -p /var/spool/samba
    chmod 1777 /var/spool/samba
    mkdir -p /var/lib/samba/printers
    systemctl restart smbd nmbd
    print_success "Samba instalado"
    
    # Maven
    print_header "7/9 - INSTALANDO MAVEN"
    apt-get install -y maven
    MVN_VER=$(mvn -version | head -n1 | awk '{print $3}')
    print_success "Maven $MVN_VER instalado"
    
    # Usuario
    print_header "8/9 - PREPARANDO APLICACIÓN"
    if ! id "$APP_USER" &>/dev/null; then
        useradd -r -s /bin/false -d "$APP_DIR" -m "$APP_USER"
        print_success "Usuario $APP_USER creado"
    else
        print_success "Usuario ya existe"
    fi
    
    # Copiar archivos
    CURRENT_DIR=$(pwd)
    mkdir -p "$APP_DIR"
    
    if [ ! -f "$CURRENT_DIR/pom.xml" ]; then
        print_error "No se encontró pom.xml"
        exit 1
    fi
    
    print_info "Copiando archivos..."
    cp -r "$CURRENT_DIR"/* "$APP_DIR/"
    mkdir -p "$APP_DIR/data/uploads"
    chown -R "$APP_USER:$APP_USER" "$APP_DIR"
    print_success "Archivos copiados a $APP_DIR"
    
    # Configurar
    print_info "Configurando application.properties..."
    cat > "$APP_DIR/src/main/resources/application.properties" << EOFAPP
server.port=$APP_PORT
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.url=jdbc:postgresql://localhost:5432/$DB_NAME
spring.datasource.username=$DB_USER
spring.datasource.password=$DB_PASS
spring.jpa.hibernate.ddl-auto=update
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.show-sql=false
spring.jpa.defer-datasource-initialization=true
spring.sql.init.mode=always
logging.level.root=info
logging.level.es.ucm.fdi.iu=info
es.ucm.fdi.base-path=$APP_DIR/data/uploads
samba.guest.access=yes
samba.browseable=yes
spring.thymeleaf.cache=true
EOFAPP
    print_success "Configuración lista"
    
    # Compilar
    print_header "9/9 - COMPILANDO APLICACIÓN"
    print_info "Descargando dependencias y compilando..."
    print_info "Este proceso puede tardar 5-10 minutos..."
    cd "$APP_DIR"
    
    if sudo -u "$APP_USER" mvn clean package -DskipTests 2>&1 | tee /tmp/mvn-build.log | grep -E "Building|SUCCESS"; then
        if [ -f "$APP_DIR/target/iu-0.0.1-SNAPSHOT.jar" ]; then
            JAR_SIZE=$(du -h "$APP_DIR/target/iu-0.0.1-SNAPSHOT.jar" | cut -f1)
            print_success "Compilación exitosa - JAR: $JAR_SIZE"
        else
            print_error "JAR no generado"
            tail -50 /tmp/mvn-build.log
            exit 1
        fi
    else
        print_error "Error en compilación"
        tail -50 /tmp/mvn-build.log
        exit 1
    fi
    
    # Servicio systemd
    print_header "CREANDO SERVICIO SYSTEMD"
    cat > /etc/systemd/system/print-manager.service << EOFSVC
[Unit]
Description=Print Manager - Sistema de Gestión de Impresoras
After=network.target postgresql.service cups.service

[Service]
Type=simple
User=$APP_USER
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/java -jar $APP_DIR/target/iu-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOFSVC
    
    systemctl daemon-reload
    systemctl enable print-manager
    print_success "Servicio systemd configurado"
    
    # Firewall
    print_header "CONFIGURANDO FIREWALL"
    if command -v ufw &> /dev/null; then
        ufw --force allow $APP_PORT/tcp comment 'Print Manager'
        ufw --force allow 631/tcp comment 'CUPS'
        ufw --force allow 139/tcp comment 'Samba'
        ufw --force allow 445/tcp comment 'Samba'
        ufw --force allow 22/tcp comment 'SSH'
        print_success "Reglas de firewall añadidas"
    fi
    
    # Iniciar servicio
    print_header "INICIANDO SERVICIO"
    print_info "Iniciando Print Manager..."
    systemctl start print-manager
    
    print_info "Esperando inicio de la aplicación..."
    for i in {1..15}; do
        sleep 2
        if systemctl is-active --quiet print-manager; then
            print_success "Servicio activo"
            
            # Esperar a que la app responda
            sleep 5
            if curl -s http://localhost:$APP_PORT > /dev/null 2>&1; then
                print_success "Aplicación respondiendo en puerto $APP_PORT"
                break
            fi
        fi
        
        if [ $i -eq 15 ]; then
            print_error "Timeout esperando inicio"
            print_info "Verificando logs..."
            journalctl -u print-manager -n 50 --no-pager
        fi
    done
}

main() {
    clear
    check_root
    
    cat << 'BANNER'
╔═══════════════════════════════════════════════════════════════╗
║     🖨️  INSTALADOR DE SISTEMA DE GESTIÓN DE IMPRESORAS       ║
║              CUPS + Samba + Spring Boot                      ║
╚═══════════════════════════════════════════════════════════════╝
BANNER
    
    echo ""
    print_info "Este script instalará todos los componentes necesarios"
    echo ""
    read -p "¿Continuar con la instalación? (s/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Ss]$ ]]; then
        exit 0
    fi
    
    START=$(date +%s)
    install_system
    END=$(date +%s)
    DURATION=$((END - START))
    
    IP=$(hostname -I | awk '{print $1}')
    
    print_header "✓ INSTALACIÓN COMPLETADA"
    cat << EOFINFO

${GREEN}╔═══════════════════════════════════════════════════════════════╗
║               INSTALACIÓN EXITOSA                             ║
╚═══════════════════════════════════════════════════════════════╝${NC}

${BLUE}📱 ACCESO A LA APLICACIÓN:${NC}
   
   🌐 URL:       http://$IP:$APP_PORT
   👤 Usuario:   admin
   🔐 Password:  admin
   
   ${YELLOW}⚠️  Cambia la contraseña después del primer login${NC}

${BLUE}🖨️  SERVICIOS ADICIONALES:${NC}
   
   CUPS Web:     http://$IP:631
   Samba:        \\\\$IP\\printers

${BLUE}🔧 COMANDOS ÚTILES:${NC}
   
   Estado:       sudo systemctl status print-manager
   Logs:         sudo journalctl -u print-manager -f
   Reiniciar:    sudo systemctl restart print-manager
   Detener:      sudo systemctl stop print-manager

${BLUE}📁 UBICACIÓN:${NC}
   
   Aplicación:   $APP_DIR
   Logs:         sudo journalctl -u print-manager
   Config:       $APP_DIR/src/main/resources/application.properties

Tiempo de instalación: ${DURATION}s

${GREEN}¡Sistema listo para usar!${NC}

EOFINFO
}

main "$@"
ENDOFSCRIPT

# Dar permisos
chmod +x install-ubuntu-fixed.sh

# Ejecutar la versión corregida
sudo ./install-ubuntu-fixed.sh