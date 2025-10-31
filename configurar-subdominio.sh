#!/bin/bash

# Script para configurar subdominio ueb-impresoras.ueb.edu.ec en Ubuntu Server
# Este script configura NGINX como proxy inverso

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuración
SUBDOMINIO="ueb-impresoras.ueb.edu.ec"
IP_SERVIDOR="10.1.16.31"
PUERTO_APP="8080"

echo -e "${BLUE}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     Configuración de Subdominio para Print Manager       ║${NC}"
echo -e "${BLUE}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""

# Verificar que se ejecuta como root
if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}✗ Este script debe ejecutarse como root (sudo)${NC}"
   exit 1
fi

echo -e "${GREEN}✓ Permisos de root verificados${NC}"
echo ""

# Información
echo -e "${YELLOW}Configuración que se aplicará:${NC}"
echo -e "  Subdominio: ${GREEN}$SUBDOMINIO${NC}"
echo -e "  IP Servidor: ${GREEN}$IP_SERVIDOR${NC}"
echo -e "  Puerto App: ${GREEN}$PUERTO_APP${NC}"
echo ""
read -p "¿Continuar? (s/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Ss]$ ]]; then
    exit 0
fi

# PASO 1: Actualizar sistema
echo ""
echo -e "${BLUE}[1/7] Actualizando sistema...${NC}"
apt-get update -qq
echo -e "${GREEN}✓ Sistema actualizado${NC}"

# PASO 2: Instalar NGINX
echo ""
echo -e "${BLUE}[2/7] Instalando NGINX...${NC}"
if systemctl is-active --quiet nginx 2>/dev/null; then
    echo -e "${GREEN}✓ NGINX ya está instalado y activo${NC}"
else
    apt-get install -y nginx
    systemctl enable nginx
    systemctl start nginx
    echo -e "${GREEN}✓ NGINX instalado y activado${NC}"
fi

# PASO 3: Crear configuración del sitio
echo ""
echo -e "${BLUE}[3/7] Creando configuración de NGINX...${NC}"

# Backup si existe
if [ -f /etc/nginx/sites-available/ueb-impresoras ]; then
    cp /etc/nginx/sites-available/ueb-impresoras /etc/nginx/sites-available/ueb-impresoras.backup.$(date +%Y%m%d_%H%M%S)
    echo -e "${YELLOW}  ⚠ Backup de configuración anterior creado${NC}"
fi

# Crear configuración
cat > /etc/nginx/sites-available/ueb-impresoras << 'EOFNGINX'
server {
    listen 80;
    server_name ueb-impresoras.ueb.edu.ec;
    
    # Logs específicos
    access_log /var/log/nginx/ueb-impresoras-access.log;
    error_log /var/log/nginx/ueb-impresoras-error.log;
    
    # Tamaño máximo de archivo (para PDFs grandes)
    client_max_body_size 100M;
    
    # Proxy al puerto 8080 (aplicación Java)
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
        
        # Timeouts para operaciones de impresión
        proxy_connect_timeout 600s;
        proxy_send_timeout 600s;
        proxy_read_timeout 600s;
        
        # Buffering
        proxy_buffering off;
        proxy_request_buffering off;
    }
    
    # WebSocket support (si se necesita en el futuro)
    location /ws {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }
}
EOFNGINX

echo -e "${GREEN}✓ Configuración de NGINX creada${NC}"

# PASO 4: Habilitar sitio
echo ""
echo -e "${BLUE}[4/7] Habilitando sitio...${NC}"

# Remover enlace simbólico si existe
if [ -L /etc/nginx/sites-enabled/ueb-impresoras ]; then
    rm /etc/nginx/sites-enabled/ueb-impresoras
fi

# Crear enlace simbólico
ln -s /etc/nginx/sites-available/ueb-impresoras /etc/nginx/sites-enabled/

# Desactivar sitio default si existe (opcional)
if [ -L /etc/nginx/sites-enabled/default ]; then
    echo -e "${YELLOW}  ⚠ Sitio default detectado. ¿Desactivarlo? (recomendado)${NC}"
    read -p "    Desactivar default? (s/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Ss]$ ]]; then
        rm /etc/nginx/sites-enabled/default
        echo -e "${GREEN}  ✓ Sitio default desactivado${NC}"
    fi
fi

echo -e "${GREEN}✓ Sitio habilitado${NC}"

# PASO 5: Verificar configuración
echo ""
echo -e "${BLUE}[5/7] Verificando configuración de NGINX...${NC}"
if nginx -t 2>&1 | grep -q "successful"; then
    echo -e "${GREEN}✓ Configuración válida${NC}"
else
    echo -e "${RED}✗ Error en configuración de NGINX${NC}"
    nginx -t
    exit 1
fi

# PASO 6: Configurar firewall
echo ""
echo -e "${BLUE}[6/7] Configurando firewall...${NC}"
if command -v ufw &> /dev/null; then
    # Verificar si UFW está activo
    if ufw status | grep -q "Status: active"; then
        ufw allow 80/tcp comment 'HTTP - Print Manager' >/dev/null 2>&1
        ufw allow 443/tcp comment 'HTTPS - Print Manager' >/dev/null 2>&1
        echo -e "${GREEN}✓ Reglas de firewall añadidas${NC}"
        ufw status numbered | grep -E "80|443"
    else
        echo -e "${YELLOW}  ⚠ UFW no está activo. Reglas no aplicadas${NC}"
        echo -e "${YELLOW}    Para activar: sudo ufw enable${NC}"
    fi
else
    echo -e "${YELLOW}  ⚠ UFW no está instalado${NC}"
fi

# PASO 7: Reiniciar servicios
echo ""
echo -e "${BLUE}[7/7] Reiniciando servicios...${NC}"

# Reiniciar NGINX
systemctl restart nginx
echo -e "${GREEN}✓ NGINX reiniciado${NC}"

# Verificar que la aplicación está corriendo
if systemctl is-active --quiet print-manager 2>/dev/null; then
    echo -e "${GREEN}✓ Print Manager está activo${NC}"
else
    echo -e "${YELLOW}  ⚠ Print Manager no está activo como servicio${NC}"
    # Verificar si algo está escuchando en el puerto 8080
    if netstat -tuln 2>/dev/null | grep -q ":8080 " || ss -tuln 2>/dev/null | grep -q ":8080 "; then
        echo -e "${GREEN}  ✓ Pero hay algo escuchando en puerto 8080${NC}"
    else
        echo -e "${YELLOW}  ⚠ No se detecta aplicación en puerto 8080${NC}"
        echo -e "${YELLOW}    Asegúrate de iniciar la aplicación Java${NC}"
    fi
fi

# RESUMEN FINAL
echo ""
echo -e "${GREEN}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║            ✓ CONFIGURACIÓN COMPLETADA                     ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${BLUE}📋 INFORMACIÓN IMPORTANTE:${NC}"
echo ""
echo -e "${YELLOW}1. DNS (IMPORTANTE - Hazlo primero):${NC}"
echo -e "   Debes configurar el DNS para que apunte:"
echo -e "   ${GREEN}$SUBDOMINIO → $IP_SERVIDOR${NC}"
echo ""
echo -e "   En tu servidor DNS, agrega un registro tipo A:"
echo -e "   ${BLUE}Nombre:${NC} ueb-impresoras"
echo -e "   ${BLUE}Tipo:${NC}   A"
echo -e "   ${BLUE}Valor:${NC}  $IP_SERVIDOR"
echo -e "   ${BLUE}TTL:${NC}    3600"
echo ""
echo -e "${YELLOW}2. Acceso al sistema:${NC}"
echo -e "   URL: ${GREEN}http://$SUBDOMINIO${NC}"
echo -e "   También accesible vía IP: ${GREEN}http://$IP_SERVIDOR${NC}"
echo ""
echo -e "${YELLOW}3. Verificar funcionamiento:${NC}"
echo -e "   ${BLUE}# Verificar NGINX:${NC}"
echo -e "   sudo systemctl status nginx"
echo ""
echo -e "   ${BLUE}# Ver logs de acceso:${NC}"
echo -e "   sudo tail -f /var/log/nginx/ueb-impresoras-access.log"
echo ""
echo -e "   ${BLUE}# Ver logs de errores:${NC}"
echo -e "   sudo tail -f /var/log/nginx/ueb-impresoras-error.log"
echo ""
echo -e "   ${BLUE}# Probar localmente:${NC}"
echo -e "   curl http://localhost:8080"
echo -e "   curl http://$SUBDOMINIO"
echo ""
echo -e "${YELLOW}4. Comandos útiles:${NC}"
echo -e "   ${BLUE}# Reiniciar NGINX:${NC}"
echo -e "   sudo systemctl restart nginx"
echo ""
echo -e "   ${BLUE}# Verificar configuración:${NC}"
echo -e "   sudo nginx -t"
echo ""
echo -e "   ${BLUE}# Ver estado de servicios:${NC}"
echo -e "   sudo systemctl status nginx"
echo -e "   sudo systemctl status print-manager"
echo ""
echo -e "${YELLOW}5. SSL/HTTPS (Opcional pero recomendado):${NC}"
echo -e "   Para habilitar HTTPS con certificado SSL:"
echo -e "   ${BLUE}sudo apt-get install certbot python3-certbot-nginx -y${NC}"
echo -e "   ${BLUE}sudo certbot --nginx -d $SUBDOMINIO${NC}"
echo ""
echo -e "${GREEN}✓ Todo listo! Ahora configura el DNS y podrás acceder.${NC}"
echo ""
