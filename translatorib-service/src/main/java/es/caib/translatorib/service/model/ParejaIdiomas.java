package es.caib.translatorib.service.model;

// Clase interna para representar una relación entre dos idiomas
public class ParejaIdiomas {
    private Idioma origen;
    private Idioma destino;

    public ParejaIdiomas(Idioma origen, Idioma destino) {
        this.origen = origen;
        this.destino = destino;
    }

    public Idioma getOrigen() {
        return origen;
    }

    public void setOrigen(Idioma origen) {
        this.origen = origen;
    }

    public Idioma getDestino() {
        return destino;
    }

    public void setDestino(Idioma destino) {
        this.destino = destino;
    }

    @Override
    public String toString() {
        return origen.getIdioma() + "#" + origen.getLocale() + "##" + destino.getIdioma() + "#" + destino.getLocale();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ParejaIdiomas that = (ParejaIdiomas) obj;
        return origen.equals(that.origen) && destino.equals(that.destino);
    }

    @Override
    public int hashCode() {
        return 31 * origen.hashCode() + destino.hashCode();
    }

    public String getLabel() {
        return "parejaIdiomas." + origen.getIdioma() + "#" + origen.getLocale() + "##" + destino.getIdioma() + "#" + destino.getLocale();
    }
}
