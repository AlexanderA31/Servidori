# 📖 Cómo Agregar Impresoras desde Clientes

## Guía Rápida para Usuarios

Esta guía explica cómo conectarse a las impresoras del servidor desde cualquier computadora cliente.

---

## 🔍 Encontrar Información de la Impresora

Antes de agregar una impresora, necesitas esta información:

1. **IP del servidor**: Ejemplo: `192.168.1.100`
2. **Nombre de la impresora**: Ejemplo: `HP_LaserJet_Pro`

### Dónde Encontrar Esta Información:

#### Opción 1: Interfaz Web del Servidor
1. Abre navegador: `http://IP-DEL-SERVIDOR:8080`
2. Inicia sesión
3. Ve a "Impresoras" o "CUPS/Samba Management"
4. Encuentra tu impresora
5. Copia la información mostrada:
   - **URI IPP**: `ipp://192.168.1.100:631/printers/HP_LaserJet_Pro`
   - **URI Samba**: `\\192.168.1.100\HP_LaserJet_Pro`
   - **IP Directa**: `192.168.1.100`

---

## 🪟 Windows - 3 Métodos

### Método 1: Agregar por Red (MÁS FÁCIL)

```
1. Abre "Configuración" (Windows + I)
2. Ve a "Dispositivos" > "Impresoras y escáneres"
3. Click "Agregar una impresora o escáner"
4. Espera unos segundos...
5. Si aparece tu impresora: ¡Click y listo!
6. Si NO aparece: Click "La impresora que deseo no está en la lista"
7. Selecciona "Seleccionar una impresora compartida por nombre"
8. Ingresa: \\IP-SERVIDOR\NOMBRE-IMPRESORA
   Ejemplo: \\192.168.1.100\HP_LaserJet_Pro
9. Click "Siguiente" y sigue el asistente
```

**💡 Tip**: Si te pide usuario y contraseña:
- Usuario: tu nombre de usuario en el servidor
- Contraseña: tu contraseña del servidor

### Método 2: Agregar por IPP (Más Compatible)

```
1. Panel de Control > Dispositivos e Impresoras
2. Click "Agregar una impresora"
3. Click "La impresora que deseo no está en la lista"
4. Selecciona "Agregar una impresora usando dirección TCP/IP o nombre de host"
5. Tipo de dispositivo: Puerto TCP/IP
6. Nombre de host o dirección IP: Ingresa IP de la impresora O IP del servidor
   Ejemplo: 192.168.1.100
7. Nombre del puerto: (automático)
8. Click "Siguiente"
9. Windows detectará la impresora automáticamente
10. Selecciona driver apropiado o usa el sugerido
11. Dale un nombre y finaliza
```

### Método 3: PowerShell (Para Administradores)

```powershell
# Abrir PowerShell como Administrador

# Agregar impresora via Samba
Add-Printer -ConnectionName "\\192.168.1.100\HP_LaserJet_Pro"

# O agregar via IPP
$PortName = "IP_192.168.1.100"
$PrinterIP = "192.168.1.100"
$PrinterName = "HP LaserJet Pro"
$DriverName = "HP Universal Printing PCL 6" # Usar driver instalado

# Crear puerto
Add-PrinterPort -Name $PortName -PrinterHostAddress $PrinterIP

# Agregar impresora
Add-Printer -Name $PrinterName -DriverName $DriverName -PortName $PortName

# Establecer como predeterminada (opcional)
Set-DefaultPrinter -Name $PrinterName
```

---

## 🐧 Linux - 3 Métodos

### Método 1: Interfaz Gráfica (Ubuntu/GNOME)

```
1. Abrir "Configuración" del sistema
2. Ir a "Impresoras"
3. Click en "Agregar" o el botón "+"
4. La impresora debería aparecer automáticamente
5. Si aparece: Selecciónala y click "Agregar"
6. Si NO aparece:
   - Click "Impresora de red"
   - Selecciona "Impresora IPP en red"
   - URI: ipp://192.168.1.100:631/printers/HP_LaserJet_Pro
   - O URI Samba: smb://192.168.1.100/HP_LaserJet_Pro
7. Selecciona driver apropiado
8. Click "Agregar"
```

### Método 2: Línea de Comandos (TODAS LAS DISTRIBUCIONES)

#### Agregar via IPP (Recomendado):
```bash
# Ver impresoras disponibles en el servidor
lpinfo -v | grep ipp

# Agregar impresora IPP
sudo lpadmin -p HP_LaserJet_Pro \
  -E \
  -v ipp://192.168.1.100:631/printers/HP_LaserJet_Pro \
  -m everywhere

# Habilitar impresora
sudo cupsenable HP_LaserJet_Pro
sudo cupsaccept HP_LaserJet_Pro

# Establecer como predeterminada
lpoptions -d HP_LaserJet_Pro

# Probar impresión
echo "Prueba de impresión" | lp -d HP_LaserJet_Pro
```

#### Agregar via Samba:
```bash
# Primero instalar soporte Samba
sudo apt install smbclient  # Debian/Ubuntu
sudo dnf install samba-client  # Fedora/RHEL

# Agregar impresora Samba
sudo lpadmin -p HP_LaserJet_Pro \
  -E \
  -v smb://usuario:password@192.168.1.100/HP_LaserJet_Pro \
  -m drv:///sample.drv/generic.ppd

# Habilitar
sudo cupsenable HP_LaserJet_Pro
sudo cupsaccept HP_LaserJet_Pro
```

#### Agregar Directamente a la Impresora (sin servidor):
```bash
# Si la impresora tiene IP propia en la red
sudo lpadmin -p HP_LaserJet_Pro \
  -E \
  -v ipp://192.168.1.50/ipp/print \
  -m everywhere

# O usando socket/RAW
sudo lpadmin -p HP_LaserJet_Pro \
  -E \
  -v socket://192.168.1.50:9100 \
  -m everywhere
```

### Método 3: system-config-printer (Fedora/RHEL)

```bash
# Instalar si no está disponible
sudo dnf install system-config-printer

# Ejecutar
sudo system-config-printer

# En la interfaz:
1. Click "Add"
2. Selecciona "Network Printer" > "IPP"
3. Host: 192.168.1.100
4. Queue: printers/HP_LaserJet_Pro
5. Click "Forward" y sigue el asistente
```

---

## 🍎 macOS - 2 Métodos

### Método 1: Preferencias del Sistema

```
1. Menu Apple  > Preferencias del Sistema
2. Click "Impresoras y Escáneres"
3. Click el botón "+" para agregar
4. La impresora debería aparecer automáticamente con Bonjour/AirPrint
5. Si aparece: Selecciónala y click "Agregar"
6. Si NO aparece:
   a) Pestaña "IP":
      - Protocolo: IPP
      - Dirección: 192.168.1.100
      - Cola: printers/HP_LaserJet_Pro
   b) O Pestaña "Windows":
      - Dirección: smb://192.168.1.100/HP_LaserJet_Pro
7. Usar: Selecciona el driver apropiado o "Generic PostScript"
8. Click "Agregar"
```

### Método 2: Terminal

```bash
# Agregar via IPP
lpadmin -p HP_LaserJet_Pro \
  -E \
  -v ipp://192.168.1.100:631/printers/HP_LaserJet_Pro \
  -m everywhere

# Establecer como predeterminada
lpoptions -d HP_LaserJet_Pro

# Probar
echo "Prueba" | lp -d HP_LaserJet_Pro
```

---

## 📱 Dispositivos Móviles

### Android

1. **Instalar PrintHand o similar**
   - Descarga desde Play Store
   - Abre la app
   - Click "Agregar Impresora"
   
2. **Configurar**
   - Tipo: Samba o IPP
   - Servidor: 192.168.1.100
   - Impresora: HP_LaserJet_Pro
   - Usuario/contraseña si es necesario

3. **Imprimir**
   - Desde cualquier app: Compartir > Imprimir
   - Selecciona PrintHand
   - Selecciona tu impresora

### iOS/iPadOS

1. **Si el servidor tiene AirPrint habilitado**:
   - Solo imprime: Compartir > Imprimir
   - Tu impresora aparecerá automáticamente

2. **Sin AirPrint - Usar App**:
   - Instala "PrintCentral Pro" o similar
   - Agrega servidor IPP manualmente
   - Servidor: 192.168.1.100:631
   - Ruta: /printers/HP_LaserJet_Pro

---

## 🔧 Configuraciones Avanzadas

### Ver Todas las Impresoras del Servidor

#### Windows CMD:
```cmd
net view \\192.168.1.100
```

#### Linux/macOS:
```bash
# Ver impresoras via IPP
lpinfo -v | grep 192.168.1.100

# Ver recursos Samba
smbclient -L //192.168.1.100 -N
```

### Probar Conectividad

```bash
# Verificar que el servidor responde
ping 192.168.1.100

# Verificar puerto CUPS (631)
telnet 192.168.1.100 631

# Verificar puerto Samba (445)
telnet 192.168.1.100 445

# Verificar impresora directa (si tiene IP propia)
telnet 192.168.1.50 9100
```

### Opciones de Impresión

```bash
# Linux/macOS - Ver opciones disponibles
lpoptions -p HP_LaserJet_Pro -l

# Imprimir con opciones específicas
lp -d HP_LaserJet_Pro \
   -o sides=two-sided-long-edge \
   -o media=A4 \
   -o ColorModel=Gray \
   archivo.pdf
```

---

## ❓ Solución de Problemas Comunes

### "No se puede encontrar la impresora"

```bash
# 1. Verificar conectividad de red
ping IP-SERVIDOR

# 2. Verificar que CUPS está escuchando
nmap -p 631 IP-SERVIDOR

# 3. Verificar firewall del servidor
# En el servidor:
sudo ufw allow 631/tcp
sudo ufw allow 445/tcp
```

### "Acceso denegado"

1. **Verifica credenciales**: Usuario/contraseña correctos
2. **En el servidor, agregar usuario a Samba**:
   ```bash
   sudo smbpasswd -a tu_usuario
   ```
3. **Verificar permisos en CUPS**:
   ```bash
   # En /etc/cups/cupsd.conf debe permitir tu red
   <Location />
     Allow from 192.168.1.0/24
   </Location>
   ```

### "Impresora instalada pero no imprime"

```bash
# Ver estado de la impresora
lpstat -p HP_LaserJet_Pro

# Ver cola de trabajos
lpstat -o

# Ver logs
sudo tail -f /var/log/cups/error_log

# Reiniciar CUPS
sudo systemctl restart cups
```

### "Driver no disponible"

1. **Usar driver genérico**:
   - Linux: "Generic PostScript Printer" o "everywhere"
   - Windows: "Generic / Text Only"
   - macOS: "Generic PostScript Printer"

2. **Descargar driver del fabricante**:
   - HP: hp-plugin o hplip
   - Canon: Sitio web de Canon
   - Epson: Sitio web de Epson

---

## 📞 Obtener Ayuda

Si tienes problemas:

1. **Contacta al administrador del servidor**
2. **Verifica la interfaz web**: `http://IP-SERVIDOR:8080`
3. **Revisa CUPS**: `http://IP-SERVIDOR:631`
4. **Consulta los logs en el servidor**

---

## 📋 Resumen Rápido

| Sistema | Método Recomendado | URI |
|---------|-------------------|-----|
| **Windows** | Samba | `\\192.168.1.100\HP_LaserJet_Pro` |
| **Linux** | IPP | `ipp://192.168.1.100:631/printers/HP_LaserJet_Pro` |
| **macOS** | IPP | `ipp://192.168.1.100:631/printers/HP_LaserJet_Pro` |
| **Android** | App + Samba/IPP | Via PrintHand u otra app |
| **iOS** | AirPrint o App | Automático o via app |

---

**¡Listo para Imprimir! 🖨️✨**
