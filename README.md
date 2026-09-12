# CI/CD Deploy

Инфраструктурный репозиторий для сервера: Jenkins в Docker, host-nginx и
nginx-site для фронтенда `ssau-schedule-bot`.

Это правильное место для nginx-конфигов. В репозитории приложения остаются
`Jenkinsfile` и код приложения, а server-level конфиги, certbot и публикация
портов живут здесь.

---

## Минимальные требования

- Домен (например `ci.example.com`) указывает A-записью на IP сервера
- Открыты порты 80 и 8443
- Ubuntu 20.04 / 22.04 / 24.04
- Доступ по SSH с sudo

> Jenkins публикуется по HTTPS на нестандартном порту **8443**, т.к. `443` на
> этом сервере занят другим сайтом. Порт `80` всё равно нужен — по нему идёт
> ACME-проверка Let's Encrypt и редирект HTTP → HTTPS.

Открыть порты:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 8443/tcp
```

Проверка с локального ПК (Windows PowerShell):

```powershell
Test-NetConnection <IP_ИЛИ_ДОМЕН> -Port 80
Test-NetConnection <IP_ИЛИ_ДОМЕН> -Port 8443
```

---

## Структура репозитория

    jenkins-on-server/
    │
    ├── bootstrap.sh
    ├── tune-low-memory.sh
    ├── docker-compose.yml
    ├── install-ssau-frontend-site.sh
    ├── nginx/
    │   ├── jenkins-http.conf.template
    │   ├── ssau-frontend-https-port.conf.template
    │   └── ssau-frontend-http.conf.template
    ├── jenkins-image/
    └── README.md

---

## docker-compose.yml

```yaml
name: jenkins

services:
    jenkins:
        build: ./jenkins-image
        image: jenkins-on-server-jenkins:latest
        container_name: jenkins
        restart: unless-stopped
        user: "0:0"
        # Web публикуется на host ТОЛЬКО на loopback: 127.0.0.1:18080 -> 8080.
        # Порт 50000 (inbound agents) не публикуется — в проекте не используется.
        ports:
            - "127.0.0.1:18080:8080"
        mem_limit: 640m
        logging:
            driver: json-file
            options:
                max-size: "10m"
                max-file: "3"
        environment:
            # Только JENKINS_JAVA_OPTS — его читает entrypoint образа jenkins/jenkins.
            # JAVA_OPTS не задаём: его могут подхватывать другие JVM-инструменты
            # в сборках. mem_limit выше — жёсткий потолок памяти.
            - JENKINS_JAVA_OPTS=-Xms128m -Xmx320m -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -Djenkins.install.runSetupWizard=true
        volumes:
            - jenkins_home:/var/jenkins_home
            - /var/run/docker.sock:/var/run/docker.sock

volumes:
    jenkins_home:
```

Имя Compose-проекта закреплено как `jenkins`, поэтому Docker volume всегда
называется `jenkins_jenkins_home` и не зависит от имени каталога, куда
клонирован репозиторий. На чистом сервере Compose создаст volume автоматически;
при переносе он подключит заранее восстановленный volume с тем же именем.

**Про размеры памяти.** Значения выбраны по замерам с работающего сервера, а не
на глаз. На машине 961 МБ физической памяти, и Jenkins был крупнейшим потребителем:
555 МБ суммарно — 130 МБ в RAM плюс 425 МБ, вытесненных в своп. Все двадцать
контейнеров платформы, которые он обслуживает, вместе занимают ~240 МБ.

JVM забирает заявленный heap и держит его независимо от того, идут сборки или нет,
поэтому `-Xmx512m` на такой машине — это заявка на половину всей оперативки.
`UseSerialGC` поставлен потому, что ядро одно: параллельному сборщику мусора здесь
нечего распараллеливать, а на координацию потоков он такты тратит.
`MaxMetaspaceSize` ограничивает область под классы плагинов — по умолчанию она
не ограничена и растёт с каждым установленным плагином.

Ротация логов добавлена не ради порядка: диск на сервере доходил до 93%, а
json-файл лога контейнера по умолчанию растёт без предела.

Web Jenkins публикуется на host как `127.0.0.1:18080` (только loopback) и
наружу напрямую не открыт; порт `8080` остаётся внутренним портом контейнера.
`18080` **не предназначен для публичного доступа** — внешний доступ идёт через
reverse proxy. Порт `50000` (inbound agents) не публикуется, т.к. не используется;
если он понадобится, публиковать его нужно только локально как
`127.0.0.1:15000:50000`.
Папка `jenkins-image/` нужна для сборки кастомного образа Jenkins с Docker CLI и Compose plugin.

## Статусы GitHub и повтор SCM

У одного репозитория могут быть две multibranch-джобы: backend и frontend. Jenkins отправляет
первый GitHub status **до** загрузки `Jenkinsfile`; поэтому один общий контекст
`continuous-integration/jenkins/branch` опасен: успешный frontend мог скрыть упавший backend.

Образ закрепляет плагин `github-scm-trait-notification-context`, а read-only startup script
`jenkins-image/init.groovy.d/ci_status_contexts.groovy` идемпотентно задаёт источникам GitHub
отдельные контексты:

| Multibranch job | GitHub context |
| --- | --- |
| `*-backend` | `ci/backend` |
| `*-frontend` | `ci/frontend` |

Скрипт покрывает `ssau-bot`, `calendar-service` и `admin-console`. При добавлении новой пары
джоб нужно добавить её в карту `jobContexts` в этом скрипте и выкатить Jenkins. Он также
настраивает повторное индексирование каждые пять минут. Если GitHub API временно вернул 5xx до
получения `Jenkinsfile`, следующий scan повторит SCM-запрос и создаст новый build; успешные
коммиты заново не собираются.

Для применения после изменения образа:

```bash
docker compose up -d --build jenkins
docker logs --tail 100 jenkins
```

В логе должны быть строки `[ci-status-contexts] Configured ...` только при изменении
конфигурации. Затем в конфигурации каждой multibranch-джобы появится behavior `Custom GitHub
Notification Context`.

На текущем GitHub Free-плане для приватных репозиториев API не позволяет включить required
status checks. Раздельные статусы устраняют ложный зелёный aggregate status, но блокирование
merge по ним потребует GitHub Pro/Team либо изменения видимости репозиториев.

---

## Nginx (HTTP шаблон)

```nginx
server {
    listen 80;
    server_name ${DOMAIN};

    location / {
        proxy_pass http://127.0.0.1:18080;

        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Host $http_host;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

В шаблоне только `listen 80`. HTTPS-блок (`listen 8443 ssl`) и редирект
HTTP → HTTPS добавляет Certbot во время `bootstrap.sh` командой
`certbot --nginx --https-port 8443 --redirect`. Публичный HTTPS вынесен на
**8443**, потому что `443` занят другим сайтом; upstream всегда `http://127.0.0.1:18080`.

`$http_host` и `X-Forwarded-Port` сохраняют нестандартный публичный порт в
ссылках и redirect Jenkins. `X-Forwarded-Proto` обязателен при работе через
HTTPS-прокси.

---

## Запуск

### Jenkins

```bash
sudo apt-get update -y
sudo apt-get install -y git
git clone https://github.com/golosoman-labs/jenkins-on-server.git
cd jenkins-on-server
sudo bash bootstrap.sh -d ci.example.com -e admin@example.com
```

После выполнения:

- Jenkins будет доступен по https://ci.example.com:8443
- В консоли будет выведен initialAdminPassword

### Перенос существующего Jenkins

Весь изменяемый state Jenkins хранится в Docker volume
`jenkins_jenkins_home`: задания, пользователи, плагины, credentials и ключи их
шифрования. Переносить нужно volume целиком при остановленном Jenkins.

Нельзя переносить только `credentials.xml`: без файлов
`secrets/master.key`, `secrets/hudson.util.Secret` и остальных данных из
`secrets/` Jenkins не сможет расшифровать credentials.

Перед переносом создай архив volume и его SHA-256 checksum. Архивы, содержимое
volume и секреты нельзя добавлять в Git. После восстановления проверь как
минимум:

- Jenkins открывает `/login` без повторного Initial Setup Wizard;
- задания и multibranch repositories присутствуют;
- в startup-логах нет ошибок загрузки плагинов и расшифровки secrets;
- установленная версия Jenkins совместима с восстановленными плагинами.

### Frontend site для ssau-schedule-bot

Фронт собирается отдельной Jenkins Pipeline-джобой из
`ssau-schedule-bot/frontend/Jenkinsfile` и публикует `dist` сюда:

```text
/opt/apps/ssau-schedule-frontend/current
```

Host-nginx должен раздавать этот каталог и проксировать `/api` на backend.
Если у фронта отдельный домен/поддомен, можно выпустить отдельный certbot-site:

```bash
sudo bash install-ssau-frontend-site.sh \
  -d app.example.com \
  -e admin@example.com \
  -p 8444
```

По умолчанию:

- HTTPS frontend port: `8444`
- frontend root: `/opt/apps/ssau-schedule-frontend/current`
- API upstream: `http://127.0.0.1:3100`

Если TLS пока не нужен или certbot уже настраивается вручную:

```bash
sudo bash install-ssau-frontend-site.sh -d app.example.com -n
```

После этого frontend будет доступен как:

```text
https://app.example.com:8444
```

Если нужен другой порт, передай его через `-p`. Для Let's Encrypt порт `80`
всё равно должен быть открыт снаружи: HTTP-01 challenge всегда приходит на `80`.
При нестандартном HTTPS-порте скрипт дополнительно правит certbot redirect:
`http://<domain>/` должен вести на `https://<domain>:<port>/`, а не на 443.

Если frontend должен жить на том же домене, что Jenkins, но на другом HTTPS-порту
(`https://ci.example.com:8444`), не создавай второй HTTP-site с тем же
`server_name`: nginx будет конфликтовать на `listen 80`. В этом случае
переиспользуй уже выпущенный сертификат Jenkins:

```bash
sudo bash install-ssau-frontend-site.sh \
  -d ci.example.com \
  -c ci.example.com \
  -p 8444
```

Этот режим ставит только `listen 8444 ssl` для фронта и не трогает HTTP-редирект
Jenkins на `8443`.

---

## Возможная ошибка Docker

Ошибка:

    failed to load listeners: no sockets found via socket activation

Фикс:

```bash
sudo systemctl enable --now docker.socket
sudo systemctl restart docker.socket
sudo systemctl reset-failed docker.service
```

Проверка:

```bash
docker version
docker info
```

---

## Что нужно сделать вручную

- Пройти Initial Setup Wizard Jenkins
- Указать Jenkins URL: Manage Jenkins → System → Jenkins Location

---

## Безопасность

- Web Jenkins публикуется на host только на loopback `127.0.0.1:18080`
  (наружу `0.0.0.0` не биндится); `8080` — внутренний порт контейнера
- Порт `18080` не для публичного доступа — внешний доступ только через reverse proxy
- Порт `50000` (inbound agents) не публикуется (не используется); при необходимости
  — только локально `127.0.0.1:15000:50000`
- Доступ только через HTTPS (публичный порт `8443`, т.к. `443` занят другим сайтом)
- JVM controller ограничена (`-Xms128m -Xmx320m`), память контейнера — `mem_limit: 640m`
- Для слабых серверов: не запускать тяжёлые и параллельные сборки
- Минимальная конфигурация без лишних плагинов

---

## Сервер на пределе памяти

`tune-low-memory.sh` настраивает хост под маленькую машину. Писался для сервера
платформы: 961 МБ RAM, одно ядро, 20 ГБ диска, из которых было занято 93%.

```bash
sudo ./tune-low-memory.sh
```

Четыре вещи, каждая по своей причине:

1. **zram** — сжатый своп в памяти вместо страниц на диске. Диск здесь один и тот
   же для свопа и для данных, поэтому обычный своп тормозит ровно то, ради чего
   затевался. На таком объёме zram даёт эффективно +200–400 МБ.
2. **multipathd отключается** — демон многопутевого доступа к SAN-дискам. На VPS с
   единственным `/dev/sda` не делает ничего и стоит 27 МБ.
3. **sysctl** — `swappiness` и `vfs_cache_pressure` фиксируются явно, чтобы не
   зависеть от умолчаний дистрибутива.
4. **Еженедельная уборка docker** (`/etc/cron.weekly/docker-gc`). За месяц работы
   кэш сборок вырос до 2.5 ГБ. Это опаснее нехватки памяти: своп тормозит, а
   кончившийся диск останавливает всё сразу, включая прод.

`image prune` в уборке идёт **без** `-a` намеренно: образы сервисов пересобираются
на одном ядре по несколько минут, и удалять их ради места — плохая сделка.

Скрипт идемпотентен, повторный запуск ничего не ломает. Проверка после:

```bash
swapon --show                    # /dev/zram0 с приоритетом 100
free -m                          # available вырос
systemctl is-active multipathd   # inactive
```

## Пару полезных команд

- docker stop $(docker ps -aq)
- docker rm $(docker ps -aq)
- docker rmi -f $(docker images -aq)
sudo mkdir -p /opt/apps
sudo chown -R root:root /opt/apps
sudo git clone https://github.com/golosoman/ssau-schedule-bot /opt/apps/ssau-schedule-bot
