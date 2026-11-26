package biblioteca;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongConsumer;

record Metadados(String id, String titulo) {}
interface ConsultaAcervo {
    Metadados consultar(String id);
    default void invalidar(String id) {}
}

record ConsultaOriginal(UnidadeDeTrabalho unidade, Acervo acervo) implements ConsultaAcervo {
    public Metadados consultar(String id) {
        return unidade.executar(() -> {
            Livro livro = acervo.buscar(id);
            return livro == null ? null : new Metadados(id, livro.titulo());
        });
    }
}
record ConsultaComMedicao(ConsultaAcervo origem, LongConsumer coletor) implements ConsultaAcervo {
    public void invalidar(String id) { origem.invalidar(id); }
    public Metadados consultar(String id) {
        long inicio = System.nanoTime();
        try { return origem.consultar(id); }
        finally { coletor.accept(System.nanoTime() - inicio); }
    }
}
final class ConsultaComCache implements ConsultaAcervo {
    private final ConsultaAcervo origem;
    private final ConcurrentMap<String, Metadados> cache;
    private long versao;
    private boolean desativado;

    ConsultaComCache(ConsultaAcervo origem) { this(origem, new ConcurrentHashMap<>()); }
    ConsultaComCache(ConsultaAcervo origem, ConcurrentMap<String, Metadados> cache) {
        this.origem = origem;
        this.cache = cache;
    }
    public Metadados consultar(String id) {
        long antes;
        // O bloqueio protege apenas o cache; nunca envolve a consulta transacional.
        synchronized (this) {
            antes = versao;
            if (!desativado) {
                try {
                    Metadados existente = cache.get(id);
                    if (existente != null) return existente;
                } catch (RuntimeException indisponivel) { desativado = true; }
            }
        }
        Metadados carregado = origem.consultar(id);
        synchronized (this) {
            // Uma leitura iniciada antes da invalidação não pode repovoar o cache antigo.
            if (!desativado && antes == versao && carregado != null) {
                try { cache.put(id, carregado); }
                catch (RuntimeException indisponivel) { desativado = true; }
            }
        }
        return carregado;
    }
    public void invalidar(String id) {
        synchronized (this) {
            versao++;
            try { cache.remove(id); }
            catch (RuntimeException indisponivel) { desativado = true; }
        }
        origem.invalidar(id);
    }
}
