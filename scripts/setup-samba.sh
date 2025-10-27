#!/bin/bash

# Script para configurar Samba para compartir impresoras
# Ejecutar con: sudo ./scripts/setup-samba.sh

echo "=========================================="
echo "CONFIGURACION DE SAMBA PARA IMPRESORAS"
echo "=========================================="

# Colores para output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Verificar si se ejecuta como root
if [ "$EUID" -ne 0 ]; then 
    echo -e "${RED}✗ Este script debe ejecutarse como root (sudo)${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Ejecutando como root${NC}"

# 1. Verificar si Samba está instalado
echo ""
echo "1. Verificando instalación de Samba..."
if ! command -v smbd &> /dev/null; then
    echo -e "${YELLOW}⚠ Samba no está instalado. Instalando...${NC}"
    
    # Detectar sistema operativo
    if [ -f /etc/debian_version ]; then
        # Debian/Ubuntu
        apt-get update
        apt-get install -y samba samba-common-bin
    elif [ -f /etc/redhat-release ]; then
        # RedHat/CentOS/Fedora
        yum install -y samba samba-client
    else
        echo -e "${RED}✗ Sistema operativo no soportado${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Samba instalado${NC}"
else
    echo -e "${GREEN}✓ Samba ya está instalado${NC}"
fi

# 2. Crear directorio de spool
echo ""
echo "2. Creando directorio de spool..."
if [ ! -d /var/spool/samba ]; then
    mkdir -p /var/spool/samba
    chmod 1777 /var/spool/samba
    echo -e "${GREEN}✓ Directorio /var/spool/samba creado${NC}"
else
    echo -e "${GREEN}✓ Directorio /var/spool/samba ya existe${NC}"
fi

# 3. Backup de smb.conf
echo ""
echo "3. Creando backup de smb.conf..."
if [ -f /etc/samba/smb.conf ]; then
    cp /etc/samba/smb.conf /etc/samba/smb.conf.backup.$(date +%Y%m%d_%H%M%S)
    echo -e "${GREEN}✓ Backup creado${NC}"
else
    echo -e "${YELLOW}⚠ No existe smb.conf, se creará uno nuevo${NC}"
fi

# 4. Verificar configuración global
echo ""
echo "4. Verificando configuración global..."

if ! grep -q "\[global\]" /etc/samba/smb.conf 2>/dev/null; then
    echo -e "${YELLOW}⚠ Creando configuración básica${NC}"
    cat > /etc/samba/smb.conf << 'EOF'
[global]
    workgroup = WORKGROUP
    server string = Print Server
    log file = /var/log/samba/log.%m
    max log size = 1000
    logging = file
    panic action = /usr/share/samba/panic-action %d
    server role = standalone server
    obey pam restrictions = yes
    unix password sync = yes
    passwd program = /usr/bin/passwd %u
    passwd chat = *Enter\snew\s*\spassword:* %n\n *Retype\snew\s*\spassword:* %n\n *password\supdated\ssuccessfully* .
    pam password change = yes
    map to guest = bad user
    usershare allow guests = yes
    
    # Configuración para impresoras
    load printers = yes
    printing = cups
    printcap name = cups

[printers]
    comment = All Printers
    browseable = no
    path = /var/spool/samba
    printable = yes
    guest ok = yes
    read only = yes
    create mask = 0700

[print$]
    comment = Printer Drivers
    path = /var/lib/samba/printers
    browseable = yes
    read only = yes
    guest ok = no
EOF
    echo -e "${GREEN}✓ Configuración global creada${NC}"
else
    echo -e "${GREEN}✓ Configuración global existe${NC}"
    
    # Asegurar que tiene las líneas necesarias
    if ! grep -q "load printers = yes" /etc/samba/smb.conf; then
        sed -i '/\[global\]/a\    load printers = yes' /etc/samba/smb.conf
        echo -e "${GREEN}✓ Agregada: load printers = yes${NC}"
    fi
    
    if ! grep -q "printing = cups" /etc/samba/smb.conf; then
        sed -i '/\[global\]/a\    printing = cups' /etc/samba/smb.conf
        echo -e "${GREEN}✓ Agregada: printing = cups${NC}"
    fi
    
    if ! grep -q "printcap name = cups" /etc/samba/smb.conf; then
        sed -i '/\[global\]/a\    printcap name = cups' /etc/samba/smb.conf
        echo -e "${GREEN}✓ Agregada: printcap name = cups${NC}"
    fi
fi

# 5. Verificar permisos
echo ""
echo "5. Verificando permisos..."
if [ -d /var/lib/samba/printers ]; then
    chmod 755 /var/lib/samba/printers
    echo -e "${GREEN}✓ Permisos de /var/lib/samba/printers configurados${NC}"
else
    mkdir -p /var/lib/samba/printers
    chmod 755 /var/lib/samba/printers
    echo -e "${GREEN}✓ Directorio /var/lib/samba/printers creado${NC}"
fi

# 6. Testear configuración
echo ""
echo "6. Testeando configuración..."
if testparm -s /etc/samba/smb.conf > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Configuración válida${NC}"
else
    echo -e "${RED}✗ Configuración inválida. Revisa los errores:${NC}"
    testparm -s /etc/samba/smb.conf
    exit 1
fi

# 7. Configurar firewall (si está activo)
echo ""
echo "7. Configurando firewall..."
if command -v ufw &> /dev/null; then
    if ufw status | grep -q "Status: active"; then
        ufw allow samba
        echo -e "${GREEN}✓ Reglas de firewall (ufw) configuradas${NC}"
    else
        echo -e "${YELLOW}⚠ Firewall ufw no está activo${NC}"
    fi
elif command -v firewall-cmd &> /dev/null; then
    if systemctl is-active firewalld &> /dev/null; then
        firewall-cmd --permanent --add-service=samba
        firewall-cmd --reload
        echo -e "${GREEN}✓ Reglas de firewall (firewalld) configuradas${NC}"
    else
        echo -e "${YELLOW}⚠ Firewall firewalld no está activo${NC}"
    fi
else
    echo -e "${YELLOW}⚠ No se detectó firewall (ufw o firewalld)${NC}"
fi

# 8. Habilitar e iniciar servicios
echo ""
echo "8. Habilitando e iniciando servicios..."
systemctl enable smbd nmbd 2>/dev/null || systemctl enable smb nmb 2>/dev/null
systemctl restart smbd nmbd 2>/dev/null || systemctl restart smb nmb 2>/dev/null

if systemctl is-active --quiet smbd || systemctl is-active --quiet smb; then
    echo -e "${GREEN}✓ Servicios Samba iniciados${NC}"
else
    echo -e "${RED}✗ Error al iniciar servicios Samba${NC}"
    exit 1
fi

# 9. Mostrar información
echo ""
echo "=========================================="
echo "RESUMEN DE CONFIGURACION"
echo "=========================================="
echo -e "${GREEN}✓ Samba configurado exitosamente${NC}"
echo ""
echo "Información del servidor:"
echo "  - Nombre: $(hostname)"
echo "  - IP: $(hostname -I | awk '{print $1}')"
echo "  - Grupo de trabajo: WORKGROUP"
echo ""
echo "Para agregar impresoras, usa la aplicación web o edita:"
echo "  /etc/samba/smb.conf"
echo ""
echo "Comandos útiles:"
echo "  - Ver configuración: testparm -s"
echo "  - Ver recursos compartidos: smbclient -L localhost -N"
echo "  - Reiniciar Samba: sudo systemctl restart smbd nmbd"
echo "  - Ver logs: tail -f /var/log/samba/log.smbd"
echo ""
echo "Para conectar desde Windows:"
echo "  1. Abrir 'Este equipo'"
echo "  2. Hacer clic en 'Agregar una ubicación de red'"
echo "  3. Ingresar: \\\\$(hostname -I | awk '{print $1}')\\<nombre-impresora>"
echo ""
echo "=========================================="
