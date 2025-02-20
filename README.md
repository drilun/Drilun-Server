
# 🚀 Drilun - Mensajería Segura y Privada

**Drilun** es una aplicación de mensajería avanzada que garantiza la privacidad y seguridad en la comunicación mediante el protocolo de cifrado de extremo a extremo de Signal. Este proyecto proporciona un entorno de comunicación confiable y protegido para usuarios en todo el mundo.

---

## 🔐 Protocolo de Cifrado

Drilun implementa el **Protocolo Signal**, reconocido por su alto nivel de seguridad y privacidad. Utiliza la librería **libsignal** para garantizar que los mensajes, llamadas y transferencias de archivos permanezcan protegidos en todo momento.

**Características clave del cifrado:**
- Cifrado de extremo a extremo para mensajes y llamadas.
- Generación dinámica de claves para cada sesión.
- Protección contra ataques de interceptación y repetición.
- Verificación de identidad mediante claves públicas.

---

## ⚙️ Requisitos del Sistema

Este proyecto es compatible con las siguientes plataformas:

- **Servidor:** Signal-Server en entornos basados en Linux.  
- **Clientes:** Android, iOS y escritorio (Windows, macOS y Linux).  
- **Base de datos:** PostgreSQL para la gestión de usuarios y sesiones.  

---

## 📦 Estructura del Proyecto

El ecosistema de Drilun se basa en varios componentes clave:

- **Signal-Server:** Servidor principal para el intercambio de mensajes.  
- **Signal-Android:** Aplicación móvil para dispositivos Android.  
- **Signal-iOS:** Aplicación móvil para dispositivos iOS.  
- **Signal-Desktop:** Aplicación de escritorio multiplataforma.  
- **Libsignal:** Librería criptográfica para el cifrado de extremo a extremo.  

---

## 📝 Instalación y Configuración

Sigue estos pasos para configurar tu entorno de desarrollo:

1. **Clonar el Repositorio:**  
```bash
git clone https://github.com/tu-repositorio/Drilun.git
```

2. **Instalar Dependencias:**  
```bash
cd Drilun
./gradlew build
```

3. **Configurar Variables de Entorno:**  
Asegúrate de configurar las variables necesarias en el archivo `.env` o `application.yml`.

4. **Ejecutar el Servidor:**  
```bash
./gradlew run
```

---

## 📜 Licencia

Este proyecto se distribuye bajo la **Licencia Pública General de GNU versión 3 (GPLv3)**.  
Puedes consultar la licencia completa en el archivo `LICENSE` incluido en este repositorio.  

El código original pertenece a la **Signal Foundation**. Este proyecto utiliza componentes proporcionados por dicha fundación conforme a los términos de la GPLv3.  

👉 **Más información sobre la licencia:** [https://www.gnu.org/licenses/gpl-3.0.html](https://www.gnu.org/licenses/gpl-3.0.html)

---

## 🤝 Reconocimientos y Créditos

Este proyecto no sería posible sin el increíble trabajo de la **Signal Foundation** y su protocolo de cifrado.  

- Protocolo de cifrado: [Signal Protocol](https://signal.org/docs/)  
- Librería criptográfica: [libsignal](https://github.com/signalapp/libsignal)  
- Aplicaciones cliente y servidor: [Signal GitHub](https://github.com/signalapp)  

---

## 🚀 Desarrollado por

Este proyecto es desarrollado y mantenido por el equipo de **Drilun**, comprometido con la privacidad y la seguridad digital.  

Para consultas y soporte técnico, visita:  
🌐 **[www.drilun.com](https://www.drilun.com)**  

---

## 🌐 Contribuciones

Las contribuciones son bienvenidas. Si deseas colaborar, envía un Pull Request o abre un Issue en este repositorio.  
Asegúrate de seguir las **buenas prácticas de desarrollo** y respetar la **licencia GPLv3**.

---

💙 **Gracias por confiar en Drilun para tu comunicación segura.**
