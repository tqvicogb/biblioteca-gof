package biblioteca;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

interface OuvinteDevolucao { void receber(Devolucao devolucao); }

final class PublicadorDevolucao {
    private final List<OuvinteDevolucao> ouvintes = new CopyOnWriteArrayList<>();
    private final List<String> falhas = new CopyOnWriteArrayList<>();

    void registrar(OuvinteDevolucao ouvinte) { ouvintes.add(Objects.requireNonNull(ouvinte)); }
    List<String> falhas() { return List.copyOf(falhas); }
    void publicar(Devolucao evento) {
        for (OuvinteDevolucao ouvinte : ouvintes) {
            try { ouvinte.receber(evento); }
            catch (RuntimeException erro) {
                falhas.add(evento.emprestimo().id() + " / " + ouvinte.getClass().getSimpleName() + " / "
                        + erro.getMessage());
            }
        }
    }
}
final class OuvinteInspecao implements OuvinteDevolucao {
    private final Set<String> fila = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final UnidadeDeTrabalho unidade;
    private final Acervo acervo;
    OuvinteInspecao(UnidadeDeTrabalho unidade, Acervo acervo) { this.unidade = unidade; this.acervo = acervo; }
    public void receber(Devolucao evento) {
        unidade.executar(() -> {
            String id = evento.emprestimo().livro();
            Livro livro = acervo.buscar(id);
            // Um aviso atrasado não recria pendência de um exemplar já inspecionado.
            if (livro != null && livro.aguardaInspecao() && !fila.contains(id)) fila.add(id);
            return null;
        });
    }
    void concluir(String id) {
        unidade.executar(() -> {
            Livro livro = acervo.buscar(id);
            if (livro == null || !livro.aguardaInspecao()) fila.remove(id);
            return null;
        });
    }
    List<String> fila() {
        return unidade.executar(() -> {
            // Reconstitui pendências no reinício e concilia com outras instâncias.
            List<String> pendentes = acervo.pendentesDeInspecao();
            fila.clear();
            fila.addAll(pendentes);
            return pendentes;
        });
    }
}
final class OuvinteIndicadores implements OuvinteDevolucao {
    private final List<UUID> devolucoes = new CopyOnWriteArrayList<>();
    public void receber(Devolucao evento) { devolucoes.add(evento.emprestimo().id()); }
    int total() { return devolucoes.size(); }
}
record OuvinteMulta(iMulta financeiro) implements OuvinteDevolucao {
    public void receber(Devolucao evento) { financeiro.emitirMulta(evento); }
}
record OuvinteEmail(Consumer<String> remetente) implements OuvinteDevolucao {
    public void receber(Devolucao evento) {
        remetente.accept("Confirmação de devolução para " + evento.pessoa().email()
                + " (" + evento.emprestimo().livro() + ")");
    }
}

record ResumoDevolucoes(int total, List<String> falhas) {}
record TratadorDeIndicadores(OuvinteIndicadores indicadores, PublicadorDevolucao publicador) {
    ResumoDevolucoes consultar() { return new ResumoDevolucoes(indicadores.total(), publicador.falhas()); }
}
