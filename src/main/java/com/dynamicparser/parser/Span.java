package com.dynamicparser.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tramo del texto parseado que corresponde a una propiedad o elemento:
 * desde el inicio de la clave (o del elemento) hasta el fin de su valor.
 *
 * La ruta se guarda ENLAZADA (segmento + padre) y la lista completa se arma sólo
 * cuando se pide: copiarla en cada tramo sería O(profundidad²) en memoria y tiempo.
 *
 * @param start offset inicial (absoluto en el texto completo, tras LogExtractor)
 * @param end   offset final (exclusivo)
 * @param ruta  último segmento de la ruta con enlace a sus ancestros (null = raíz)
 */
public record Span(int start, int end, Ruta ruta) {

    /** Segmento de ruta enlazado: String = clave, Integer = índice. */
    public record Ruta(Object segmento, Ruta padre) {
        public List<Object> lista() {
            var out = new ArrayList<Object>();
            for (Ruta r = this; r != null; r = r.padre) out.add(r.segmento);
            Collections.reverse(out);
            return out;
        }
    }

    /** Ruta completa desde la raíz de la entrada: String = clave, Integer = índice. */
    public List<Object> path() {
        return ruta == null ? List.of() : ruta.lista();
    }

    public boolean contiene(int offset) {
        return offset >= start && offset < end;
    }

    public int largo() {
        return end - start;
    }

    public Span desplazado(int base) {
        return new Span(start + base, end + base, ruta);
    }
}
