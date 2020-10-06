package es.caib.translatorib.ejb.api.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "Resultado")
public class Resultado {

	/** Campos **/
	private boolean error = true;
	private String descripcionError;
	private String textoTraducido;

	public boolean isError() {
		return error;
	}

	public void setError(final boolean error) {
		this.error = error;
	}

	public String getDescripcionError() {
		return descripcionError;
	}

	public void setDescripcionError(final String descripcionError) {
		this.descripcionError = descripcionError;
	}

	public String getTextoTraducido() {
		return textoTraducido;
	}

	public void setTextoTraducido(final String textoTraducido) {
		this.textoTraducido = textoTraducido;
	}

}
