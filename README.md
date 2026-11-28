# AH Flipper (Fabric 26.2, client-side)

Upload everything in this zip to the root of your GitHub repo (keep the folders), then open the
Actions tab -> build -> download the ahflipper-jar artifact.

- .github/workflows/build.yml : builds the jar (fetches the Fabric template itself)
- src/main/java/dev/flipper/  : the mod's 9 Java files

In game: /flip on | off | stop | status | debug | ui | auto on|off | purse <n>
Delete .minecraft/config/ahflipper.json once after updating so new defaults are written.
Alert-only mode is default; /flip auto on makes it click menus (bannable on Hypixel).
Past-sales data: SkyCofl (https://sky.coflnet.com).
