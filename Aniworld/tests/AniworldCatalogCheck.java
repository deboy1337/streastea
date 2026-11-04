import com.example.aniworld.AniworldCatalog;
import org.jsoup.Jsoup;
import java.io.File;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AniworldCatalogCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String base = "https://aniworld.to";
        var parser = AniworldCatalog.INSTANCE;
        var fixture = Jsoup.parse("<div class='seriesList'>"
            + "<div class='genre'><div class='seriesGenreList'><h3>Abenteuer</h3></div>"
            + "<ul><li><a href='/anime/stream/one-piece'>One Piece</a></li></ul></div>"
            + "<div class='genre'><div class='seriesGenreList'><h3>Action</h3></div>"
            + "<ul><li><a href='/anime/stream/naruto'>Naruto</a></li></ul></div></div>"
            + "<h3>Hilfe</h3><ul><li><a href='/support'>Support</a></li></ul>", base);
        var rows = parser.genres(fixture, base);
        check(rows.size() == 2, "Wrapped genre headings must be found");
        check(rows.get(0).getTitle().equals("Abenteuer"), "Genre title must be retained");
        check(rows.get(0).getAnime().size() == 1, "Genres must not absorb adjacent lists");
        check(rows.get(1).getAnime().get(0).getTitle().equals("Naruto"), "Second genre must be independent");
        var lazy = Jsoup.parse("<img src='data:image/png;base64,AAAA' data-src='/public/img/cover/one.jpg'>");
        check((base + "/public/img/cover/one.jpg").equals(parser.poster(lazy, base)), "Lazy poster must beat placeholder");
        check(parser.poster(Jsoup.parse("<img src='data:image/png;base64,AAAA'>"), base) == null,
            "Placeholder must not become a cover");
        check(parser.genres(Jsoup.parse("<h1>Just a moment</h1>"), base).isEmpty(), "Challenge is not a catalogue");

        var genres = Jsoup.parse(new File(args[0]), "UTF-8", base);
        var realRows = parser.genres(genres, base);
        Set<String> actual = new HashSet<>();
        realRows.forEach(row -> row.getAnime().forEach(anime -> actual.add(anime.getUrl())));
        Set<String> expected = new HashSet<>();
        genres.select(".seriesList a[href]").forEach(link -> {
            URI url = URI.create(base).resolve(link.attr("href"));
            if (url.getPath().matches("/anime/stream/[^/]+/?")) expected.add(url.toString());
        });
        check(realRows.size() >= 20, "Real catalogue must contain all genre rows");
        check(actual.size() > 500 && actual.equals(expected), "All inventoried anime must reach the sync input");
        System.out.println("PASS: " + realRows.size() + " genres, " + actual.size() + " unique anime for cover sync");

        var home = Jsoup.parse(new File(args[1]), "UTF-8", base);
        var homeRows = parser.home(home, base);
        check(homeRows.stream().map(AniworldCatalog.Row::getTitle).toList().equals(List.of(
            "Beliebt bei AniWorld", "Neue Animes", "Derzeit beliebt", "Das sehen andere AniWorld Nutzer")),
            "All four requested home sections must be present in order");
        for (var row : homeRows) {
            check(!row.getAnime().isEmpty(), "Home row must not be empty");
            for (var anime : row.getAnime()) {
                check(anime.getPoster() != null && anime.getPoster().startsWith("https://"), "Home poster missing");
                check(!anime.getUrl().contains("/staffel-"), "Latest-episode links must not leak into other rows");
            }
            System.out.println("PASS: " + row.getTitle() + " — " + row.getAnime().size() + " anime with covers");
        }
        System.out.println("PASS: nested genre regression, row isolation, placeholders and actual site HTML");
    }
}
