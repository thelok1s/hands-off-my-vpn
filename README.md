[![Made with AI](https://img.shields.io/badge/Made%20with-AI-orange?style=for-the-badge)](https://github.com/mefengl/made-by-ai)
> **Built with [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) + Dobby**

# [RU] HandsOffMyVPN (HOMVPN)
HandsOffMyVPN (HOMVPN) — это модуль LSPosed для Android, который перехватывает и подменяет данные для известных методов обнаружения VPN, заставляя целевые приложения думать, что активное VPN-соединение отсутствует.

## Зачем?

C 15 апреля 2026 года по требованию мин-"цифры" компании стали ограничить доступ к интернет-сервисам пользователям, у которых на устройстве включен VPN.  

Приложения уже давно следят за активным VPN-соединением и просят его отключить. Однако, в апреле 2026 года было проведено исследование, которое показало, что многие популярные российские приложения нарушают приватность пользователей, различными методами исследуя устройство ради обнаружения VPN соединений и программ, далее передавая их на свои сервера, откуда они могут попасть в руки третьих лиц.     

Приложения эксплоатируют слабую защиту приватности системы Android в области сетевых подключений, чтобы ограничивать подключение с VPN (даже если активно туннелирование), компрометировать VPN-сервера (как MAX), либо собирать излишнее количество данных, при этом защищаясь от исследования механизмов сбора, но не защищая их от утечки.

## Требования
* **Android**: 9.0+ (API 28)  
* **Root**: Требуется (Magisk / KernelSU / APatch)
* **LSPosed-совместимый фреймворк**: v1.9.2+

## Методы противодействия

### Уровень Java
| API                   | Метод                               | Результат                                                                    |
|-----------------------|-------------------------------------|------------------------------------------------------------------------------|
| `NetworkCapabilities` | `hasTransport` / `getTransportInfo` | Возвращает `false` для `TRANSPORT_VPN` и `null` для информации о транспорте. |
| `ConnectivityManager` | `getNetworkCapabilities`            | Удаляет флаг VPN и устанавливает `NET_CAPABILITY_NOT_VPN`.                   |
| `LinkProperties`      | `getInterfaceName`                  | Заменяет `tun0` на `wlan0` или другие реальные имена интерфейсов.            |
| `ProxySelector`       | `select(URI)`                       | Подменяет возвращаемое значение на `listOf(Proxy.NO_PROXY)`.                 |

### Нативный уровень
| Команды          | Результат                                                                            |
|------------------|--------------------------------------------------------------------------------------|
| `getifaddrs`     | Фильтрует все туннельные интерфейсы (`tun`, `tap`, `wg`, `ipsec`).                   |
| `ioctl`          | Перехватывает `SIOCGIFCONF`, чтобы удалить VPN-интерфейсы из низкоуровневых списков. |
| `open` / `read`  | Очищает `/proc/net/dev`, `/proc/net/tcp` и `/proc/self/maps`.                        |
| `if_nametoindex` | Возвращает `0` для любых VPN-интерфейсов.                                            |

## Установка
1. Скачайте последний APK из [Релизов](../../releases)
2. Установите на ваше устройство
3. Откройте **LSPosed Manager** -> **Модули**.
4. Включите **HandsOffMyVPN** и выберите целевые приложения во вкладке **Область (Scope)**.
5. Принудительно остановите и перезапустите целевые приложения.
### Дополнительно:
1. В настройках VPN-клиента настройте раздельное туннелирование (split tunneling) – исключите российские приложения и ресурсы (либо включите vpn только для нужных вам приложений). Также, настройте маршрутизацию – скачайте geoip:ru для обхода трафика к российским ресурсам.
2. Установите LSposed-модуль Hide my applist (скорее всего, он уже у вас есть). По своему усмотрению скройте только VPN и Root приложения (черный список), либо включите режим белого списка (хехе) – некоторые приложения могут перестать открываться.
3. Проверьте работоспособность конфигурации применив модуль к VPN Detector

## Сборка из исходников
```bash
git clone https://github.com/thelok1s/hands-off-my-vpn.git
cd hands-off-my-vpn
./gradlew build
```
**Требования**: JDK 17, Android SDK (API 35), NDK (r27).


---

# [EN] HandsOffMyVPN (HOMVPN)
HandsOffMyVPN (HOMVPN) is an LSPosed module for Android that intercepts and spoofs data for known VPN detection methods, making target applications believe no active VPN connection is present.

## Why? 

Starting April 15, 2026, Russian companies began restricting access to Internet services for users with active VPNs per government requirements.

Apps have long monitored for active VPN connections and requested their deactivation. However, an April 2026 study revealed that many popular apps violate user privacy by investigating devices using various methods to detect VPN connections and software, subsequently transmitting this data to their servers where it may fall into third-party hands.

If your government restricts free access to the Internet, strives to detect and block VPN services and your local apps want to spy on you without your consent – you might want to use this module.

## Requirements
* **Android**: 9.0+ (API 28)  
* **Root**: Required (Magisk / KernelSU / APatch)
* **LSPosed-compatible Framework**:  v1.9.2+

## Counter methods

### Java Layer
| API                   | Method                              | Behavior                                                                   |
|-----------------------|-------------------------------------|----------------------------------------------------------------------------|
| `NetworkCapabilities` | `hasTransport` / `getTransportInfo` | Returns `false` for `TRANSPORT_VPN` and returns `null` for transport info. |
| `ConnectivityManager` | `getNetworkCapabilities`            | Strips VPN transport and sets `NET_CAPABILITY_NOT_VPN`.                    |
| `LinkProperties`      | `getInterfaceName`                  | Replaces `tun0` with `wlan0` or other physical interface names.            |
| `ProxySelector`       | `select(URI)`                       | Spoofs return value to `listOf(Proxy.NO_PROXY)`.                           |

### Native Layer (libc)
| Function         | Targeted Detection                                                      |
|------------------|-------------------------------------------------------------------------|
| `getifaddrs`     | Filters out all tunnel-like interfaces (`tun`, `tap`, `wg`, `ipsec`).   |
| `ioctl`          | Intercepts `SIOCGIFCONF` to remove VPN interfaces from low-level lists. |
| `open` / `read`  | Sanitizes `/proc/net/dev`, `/proc/net/tcp`, and `/proc/self/maps`.      |
| `if_nametoindex` | Returns `0` for any VPN interface name.                                 |

## Installation 
1. Download the latest APK from [Releases](../../releases).
2. Install it on your device: `adb install HandsOffMyVPN.apk`.
3. Open **LSPosed Manager** -> **Modules**.
4. Enable **HandsOffMyVPN** and select target apps in the **Scope** tab.
5. Force-stop and relaunch target apps.

## Building from source 
```bash
git clone https://github.com/thelok1s/hands-off-my-vpn.git
cd hands-off-my-vpn
./gradlew assembleDebug
```
**Requirements**: JDK 17, Android SDK (API 35), NDK (r27).

---

## Architecture / Архитектура
- `app/src/main/kotlin/.../HookEntry.kt`: Main entry point for LSPosed.
- `app/src/main/cpp/`: Native C++ payload using Dobby for inline hooking.
- `app/src/main/kotlin/.../ui/`: Modern Jetpack Compose management interface.

## License / Лицензия
MIT License — see [LICENSE](LICENSE) file for full text.
