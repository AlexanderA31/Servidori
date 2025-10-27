# Crear el script de actualización automática
cat > ~/update-print-manager.sh << 'ENDOFSCRIPT'
#!/bin/bash

##############################################################################
# Script de Actualización Automática - Print Manager
# Actualiza el código desde Git, recompila y reinicia el servicio
##############################################################################

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_step() {
    echo -e "${BLUE}▶${NC} $1"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

echo ""
echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Actualizando Print Manager desde Git                     ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# 1. Ir al directorio del proyecto
print_step "Navegando al directorio del proyecto..."
cd ~/Servidori
print_success "En directorio: $(pwd)"

# 2. Guardar cambios locales (si hay)
print_step "Guardando cambios locales..."
git stash
print_success "Cambios guardados"

# 3. Actualizar desde Git
print_step "Descargando últimos cambios desde GitHub..."
git pull origin main
print_success "Código actualizado"

# 4. Verificar que NetworkUtils tiene el nuevo método
print_step "Verificando cambios en el código..."
if grep -q "getServerIpAddress" src/main/java/es/ucm/fdi/iu/util/NetworkUtils.java; then
    print_success "Método getServerIpAddress() encontrado"
else
    print_error "ADVERTENCIA: El método getServerIpAddress() no está en NetworkUtils.java"
fi

# 5. Limpiar compilaciones anteriores
print_step "Limpiando compilaciones anteriores..."
rm -rf target
print_success "Target limpio"

# 6. Compilar el proyecto
print_step "Compilando proyecto (puede tardar 1-2 minutos)..."
mvn clean package -DskipTests -q
if [ $? -eq 0 ]; then
    print_success "Compilación exitosa"
else
    print_error "Error en la compilación"
    exit 1
fi

# 7. Verificar que el JAR se creó
if [ -f "target/iu-0.0.1-SNAPSHOT.jar" ]; then
    JAR_SIZE=$(du -h target/iu-0.0.1-SNAPSHOT.jar | cut -f1)
    print_success "JAR generado: $JAR_SIZE"
else
    print_error "JAR no encontrado"
    exit 1
fi

# 8. Detener el servicio
print_step "Deteniendo servicio print-manager..."
sudo systemctl stop print-manager
print_success "Servicio detenido"

# 9. Copiar archivos actualizados
print_step "Copiando archivos a /opt/print-manager..."
sudo cp -r target /opt/print-manager/
sudo cp -r src/main/resources/* /opt/print-manager/src/main/resources/
print_success "Archivos copiados"

# 10. Ajustar permisos
print_step "Ajustando permisos..."
sudo chown -R printmgr:printmgr /opt/print-manager
print_success "Permisos configurados"

# 11. Iniciar el servicio
print_step "Iniciando servicio print-manager..."
sudo systemctl start print-manager
print_success "Servicio iniciado"

# 12. Esperar a que inicie
print_step "Esperando inicio de la aplicación..."
sleep 15

# 13. Verificar que el servicio esté activo
if sudo systemctl is-active --quiet print-manager; then
    print_success "Servicio activo y corriendo"
else
    print_error "El servicio no está activo"
    echo ""
    echo "Ver logs con: sudo journalctl -u print-manager -n 50"
    exit 1
fi

# 14. Verificar la IP del servidor
print_step "Verificando IP del servidor..."
SERVER_IP=$(curl -s http://localhost:8080/print-server/api/printers 2>/dev/null | grep -o '"serverIp":"[^"]*"' | cut -d'"' -f4)

if [ -n "$SERVER_IP" ]; then
    if [ "$SERVER_IP" = "127.0.1.1" ] || [ "$SERVER_IP" = "127.0.0.1" ]; then
        print_error "Todavía usando IP de loopback: $SERVER_IP"
    else
        print_success "IP del servidor: $SERVER_IP"
    fi
else
    print_error "No se pudo obtener la IP del servidor"
fi

echo ""
echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  Actualización Completada                                  ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Mostrar información
echo "Información del sistema:"
echo "  • Versión Java: $(java -version 2>&1 | head -n 1)"
echo "  • Directorio: /opt/print-manager"
echo "  • Servicio: print-manager.service"
echo "  • Puerto: 8080"
if [ -n "$SERVER_IP" ]; then
    echo "  • URL: http://$SERVER_IP:8080"
fi
echo ""
echo "Comandos útiles:"
echo "  • Ver estado:  sudo systemctl status print-manager"
echo "  • Ver logs:    sudo journalctl -u print-manager -f"
echo "  • Reiniciar:   sudo systemctl restart print-manager"
echo ""
ENDOFSCRIPT

# Dar permisos de ejecución
chmod +x ~/update-print-manager.sh

echo "✓ Script creado: ~/update-print-manager.sh"