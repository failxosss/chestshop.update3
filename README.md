# SimpleShop

Jednoduchý shop plugin pro Paper/Spigot (1.20.4) ovládaný cedulemi.

## Formát cedule

```
[shop]
<cena za kus>
B nebo S
(doplní se automaticky)
```

- `B` = majitel vykupuje (kupuje od hráčů)
- `S` = majitel prodává (prodává hráčům)

Cedule se staví na truhlu nebo se přidělá na její přední stranu. Truhla musí
v momentě stavění cedule obsahovat alespoň 1 kus zboží — plugin si zapamatuje
**všechny odlišné druhy** předmětů, které v ní najde (ne jen ten první).

## GUI

Po pravém kliknutí na hotovou ceduli shopu se otevře GUI, které se velikostí
přizpůsobuje počtu různých druhů zboží v truhle (3 až 6 řádků, max. 28 druhů
najednou). Klik = koupě/prodej 1 kusu dané položky, shift+klik = celá stacka.

## Build

Vyžaduje JDK 17+ a Maven s přístupem k repozitářům uvedeným v `pom.xml`
(PaperMC + JitPack pro Vault API):

```bash
mvn package
```

Výsledný `SimpleShop.jar` najdeš v `target/`.

## Závislosti za běhu

- [Paper](https://papermc.io/) 1.20.4 (nebo Spigot)
- [Vault](https://www.spigotmc.org/resources/vault.34315/) + libovolný ekonomický plugin (např. Essentials)
