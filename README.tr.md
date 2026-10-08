# CommitGap

**Test what happens between commit and delivery.**

Dual write, transactional outbox ve idempotent consumer davranışlarını yerelde, kontrollü hatalarla
ölçen bir laboratuvar.

*An open-source project by [COMPANY_NAME].* · [English](README.md)

---

## Ne işe yarar?

Temel soru şu: **Bir backend işlemi veritabanına yazma ile mesaj teslimatı arasında, ya da bir mesaj
yeniden işlenirken kesilirse iş kayıtlarına ne olur?**

CommitGap bu soruyu diyagramla değil ölçümle cevaplar. Docker içinde küçük bir sipariş/stok sistemi
çalıştırır; bir süreci tam olarak tanımlanmış bir transaction noktasında SIGKILL ile durdurur (veya
broker bağlantısını keser), toparlanmasını bekler ve ardından producer ile consumer veritabanlarını
event bazında karşılaştırır. Aynı iş yükü üç stratejide, her biri kendi ortamında çalışır:

| Strateji | Producer | Consumer |
|---|---|---|
| `naive-dual-write` | siparişi commit eder, sonra yayımlar; bekleyen event'in kalıcı kaydı yoktur | her teslimatı uygular |
| `transactional-outbox` | sipariş ve outbox satırı aynı transaction'da commit edilir; ayrı bir relay yayımlar | her teslimatı uygular |
| `outbox-idempotent` | yukarıdakiyle aynı | `(consumer_name, event_id)` kaydını stok değişikliğiyle aynı transaction'da yazar, tekrarları atlar |

Hedef kitle: backend geliştiricileri, platform ekipleri ve dağıtık sistem davranışlarını somut görmek
isteyen mühendisler.

## Hızlı başlangıç

Gereksinimler: Java 21+, Linux container çalıştıran bir Docker motoru. CommitGap makineye hiçbir şey
kurmaz; ilk kullanımda projeyi Maven Wrapper ile derler.

```bash
./commitgap doctor
./commitgap demo
```

Windows'ta PowerShell ile `.\commitgap.ps1 doctor`, `cmd.exe` ile `commitgap.cmd doctor`.

Diğer komutlar: `scenarios list`, `run --scenario <id> --strategy <strateji>`,
`compare --scenario <id>`, `report --run <run-id>`, `cleanup --run <run-id>`.

## Teknik kararlar ve gerekçeleri

Ayrıntılı kayıtlar [docs/adr/](docs/adr/README.md) altındadır; özetleri:

**Teknoloji ve sürümler.** Java 21 hedeflenir (`--release 21`). Spring Boot 4.1.1 seçildi; resmi
sistem gereksinimleri sayfasına göre Java 17–26 ile uyumlu ve Ekim 2026 itibarıyla son kararlı
sürüm. Testcontainers 2.0.5 (Docker 29'un yükselttiği minimum API sürümünü destekleyen sürüm),
picocli 4.7.7, PostgreSQL 18.6, RabbitMQ 4.3.6, Toxiproxy 2.12.0. Tüm Maven bağımlılıkları ve image
etiketleri sabittir; `latest`, snapshot ya da dinamik sürüm kullanılmaz (enforcer kuralı kontrol eder).

**Modüller.** `commitgap-core` (senaryo modeli, invariant'lar, sonuç tipleri; Spring/Docker yok),
`commitgap-runtime` (ortam, checkpoint koordinasyonu, hata uygulama, gözlem, temizlik),
`commitgap-demo` (producer/relay/consumer), `commitgap-report` (JSON + HTML), `commitgap-cli`.

**Süreç izolasyonu ve gerçek crash.** Producer, relay ve consumer ayrı container'lardır. Crash,
Docker API üzerinden SIGKILL'dir ve `docker inspect` ile container'ın durduğu ve 137 çıkış koduyla
sonlandığı doğrulanır. Exception veya graceful shutdown crash yerine sayılmaz.

**Checkpoint'ler zamanlamaya değil gerçek aşamaya bağlıdır.** Süreç, checkpoint'i ancak ilgili aşama
gerçekten gerçekleştikten sonra bildirir (`TransactionTemplate` döndükten sonra; broker'dan olumlu
confirm gelip mesaj unroutable olarak geri dönmediyse) ve runner'ı bekler. Runner varışı görür, kendi
timeline dosyasına anında yazar, sonra SIGKILL uygular. Kontrol kayıtları iş veritabanına yazılmaz.
Outbox senaryosunda relay kapalı bir kapıyla (barrier) başlar; producer hatası uygulanmadan relay
yayın yapamaz.

**Kanıt veritabanıdır.** Invariant'lar yalnızca producer ve consumer veritabanlarının gözlem sonu
snapshot'larından, event ve sipariş kimlik kümeleri üzerinden hesaplanır. Bir eksik event ile bir
fazla event satır sayısında birbirini gizleyebilir; kimlik kontrolleri ikisini de yakalar. Publish
ve delivery logları iş transaction'ının dışında, autocommit ile yazılır ve yalnızca açıklama içindir.

**Sonuç ile beklenti ayrıdır.** `CONSISTENT`, `VIOLATION_OBSERVED`, `INCONCLUSIVE`, `NOT_APPLICABLE`
verinin durumunu; *matched / not matched / not verified* gösterimin senaryo tahminine uyup uymadığını
anlatır. Kırılgan stratejinin beklenen hatayı göstermesi "matched" olur ama sonucu
`VIOLATION_OBSERVED` olarak kalır. Gözlem süresi dolduğunda bekleyen iş varsa bu kalıcı kayıp
sayılmaz, `INCONCLUSIVE` olur.

**Kuyruk derinliği `rabbitmqctl` ile ölçülür.** Geliştirme sırasında RabbitMQ management API'nin
kuyrukta mesaj varken birkaç saniye boyunca `0` gösterdiği gözlendi (istatistikler örneklenir). Bu
yüzden anlık durum `rabbitmqctl list_queues` ile okunur; management sayaçları yalnızca açıklayıcıdır.

**Broker kesintisi.** Toxiproxy yalnızca yayımlayan süreçlerin yolundadır. Kesinti, uçuşta yayın
yokken uygulanır ve en az bir yayın denemesinin başarısız olduğu görülene kadar sürer; böylece
kesinti gerçekten publisher'a ulaşmadan geri alınmaz. Bu kuralı, ilk denemelerde outbox relay'inin
kesinti boyunca hiç deneme yapmadığını entegrasyon testi yakaladıktan sonra ekledik.

**Sınırlı ve görünür retry.** Quorum queue `x-delivery-limit: 5` ve dead-letter kuyruğu kullanır;
relay sınırlı üstel bekleme ile dener ve 50 denemeden sonra satırı `PARKED` yapar. Sonsuz requeue yok.

**İdempotent consumer.** `INSERT ... ON CONFLICT DO NOTHING` ile unique anahtar karar verir;
check-then-act yarışı yoktur. Aynı event'i eş zamanlı işleyen ikinci worker'ın gerçekten kilit
beklediği `pg_stat_activity` ile doğrulanan bir entegrasyon testi vardır. Başarısız iş transaction'ı
dedup kaydını da geri alır. Kırılgan consumer'lar için `stock_movement.event_id` bilinçli olarak
unique değildir. Ayrıntı: [docs/outbox-and-idempotency.md](docs/outbox-and-idempotency.md).

**İzolasyon, temizlik ve gizli bilgiler.** Her run kendi ağını, isim önekini, label'larını, rastgele
portlarını ve üretilmiş parolalarını kullanır. Temizlik yalnızca `commitgap.managed=true` ve seçili
run id'sini taşıyan kaynakları siler; `docker system prune` kullanılmaz. CLI'de Testcontainers Ryuk
kapatılır (aksi halde `--keep` mümkün olmazdı); kesinti durumunda shutdown hook temizler.
Testcontainers hata mesajlarının container ortam değişkenlerini (parolalar dahil) içerdiğini bir
entegrasyon testi gösterdi; rapora, manifest'e ve timeline'a giden her metin bu yüzden arındırılır.

**Geçici namespace.** Şirket adı, alan adı ve Maven namespace'i verilmediği için uydurulmadı.
Kod, hiçbir gerçek kuruluşa ait olmayan `com.example.commitgap` ile derlenir; dokümantasyondaki
`[COMPANY_NAME]`, `[GITHUB_ORG]`, `[SECURITY_CONTACT]`, `[CONDUCT_CONTACT]`, `[MAVEN_NAMESPACE]`
yer tutucuları yayın öncesi doldurulmalıdır ([docs/release.md](docs/release.md)).

## Sınırlamalar

- Yalnızca paketle gelen demo uygulaması test edilebilir; harici bir backend'i bağlantı bilgisiyle
  otomatik analiz etme iddiası yoktur. Bunun için checkpoint, veri eşleştirme ve gözlem adapter'ları
  gerekir.
- Bir çalıştırmada kontrollerin sağlanması, tüm arızalara karşı dağıtık exactly-once garantisi değildir.
- Seed, kimlikleri ve iş yükünü tekrarlanabilir kılar; duvar saati ve zamanlamayı değil.
- Tek relay desteklenir; çoklu relay için claim/lease kapsam dışıdır.
- Yayınlanmış bir Maven/npm/Docker paketi veya GitHub release'i yoktur.

## Belgeler

[Mimari](docs/architecture.md) · [Senaryolar](docs/scenarios.md) · [Ölçüm](docs/measurement.md) ·
[Outbox ve idempotency](docs/outbox-and-idempotency.md) · [Build ve release](docs/release.md) ·
[ADR'ler](docs/adr/README.md) · [Katkı](CONTRIBUTING.md) · [Güvenlik](SECURITY.md)

Lisans: MIT ([LICENSE](LICENSE)).
