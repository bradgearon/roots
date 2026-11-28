#include "roots_tree.h"
#include <sqlite3.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <utility>
#include <tuple>
#include <unordered_set>

struct roots_tree {
    sqlite3 *db=nullptr;
    std::mutex mutex;
    ~roots_tree() { if(db) sqlite3_close(db); }
};
namespace {
constexpr size_t batch_max=256, dim=128; // Durable storage has no artificial fragment count ceiling.
constexpr size_t max_agents=32;
using result=roots_tree_result;
struct invalid : std::runtime_error { using std::runtime_error::runtime_error; };
struct quota : std::runtime_error { using std::runtime_error::runtime_error; };
void require(bool valid) { if(!valid) throw invalid("invalid argument"); }
thread_local std::string last_error;
void check(int code) {
    if(code!=SQLITE_OK && code!=SQLITE_ROW && code!=SQLITE_DONE)
        throw std::runtime_error("SQLite code "+std::to_string(code)+" ("+sqlite3_errstr(code)+")");
}
void exec(sqlite3 *db,const char *sql) { check(sqlite3_exec(db,sql,nullptr,nullptr,nullptr)); }
struct statement {
    sqlite3_stmt *s=nullptr;
    statement(sqlite3 *db,const char *sql) { check(sqlite3_prepare_v2(db,sql,-1,&s,nullptr)); }
    ~statement(){ sqlite3_finalize(s); }
    void text(int i,const char *v){check(sqlite3_bind_text(s,i,v,-1,SQLITE_TRANSIENT));}
    void number(int i,int64_t v){check(sqlite3_bind_int64(s,i,v));}
    bool next(){int c=sqlite3_step(s);check(c);return c==SQLITE_ROW;}
    void reset(){check(sqlite3_reset(s));check(sqlite3_clear_bindings(s));}
    int64_t integer(int i){return sqlite3_column_int64(s,i);}
    std::string str(int i){auto p=sqlite3_column_text(s,i);return p?reinterpret_cast<const char*>(p):"";}
};
struct transaction {
    sqlite3 *db; bool committed=false;
    explicit transaction(sqlite3 *d):db(d){exec(db,"BEGIN IMMEDIATE");}
    void commit(){exec(db,"COMMIT");committed=true;}
    ~transaction(){if(!committed)sqlite3_exec(db,"ROLLBACK",nullptr,nullptr,nullptr);}
};
template<class F> result guard(F f) noexcept {
    last_error.clear();
    try { f(); return ROOTS_TREE_OK; }
    catch(const invalid& e) { try {last_error=e.what();} catch(...) {} return ROOTS_TREE_INVALID; }
    catch(const quota& e) { try {last_error=e.what();} catch(...) {} return ROOTS_TREE_LIMIT; }
    catch(const std::exception& e) { try {last_error=e.what();} catch(...) {} return ROOTS_TREE_STORAGE; }
    catch(...) { return ROOTS_TREE_STORAGE; }
}
void bounded(const char *s,size_t max,bool nonempty=false){
    require(s!=nullptr);size_t n=std::strlen(s);require(n<=max&&(!nonempty||n>0));
}
int64_t scalar(sqlite3 *db,const char *sql){statement s(db,sql);if(!s.next())throw std::runtime_error("scalar");return s.integer(0);}
void advance(sqlite3 *db){exec(db,"UPDATE tree_state SET revision=revision+1 WHERE id=1");}
int64_t revision_of(sqlite3 *db){return scalar(db,"SELECT revision FROM tree_state WHERE id=1");}
struct row { int64_t id,at; std::string source,external,body,lang; };
struct edge { std::string subject,predicate,object; };
struct agent { int64_t id; std::string name,term; };
row read_row(statement &s){return {s.integer(0),s.integer(5),s.str(1),s.str(2),s.str(3),s.str(4)};}
std::string fragment_key(int64_t id){return "fragment:"+std::to_string(id);}
void add_edges(std::vector<edge> &edges,const row &r){
    auto key=fragment_key(r.id);
    edges.push_back({key,"is.a","type:observed-text-fragment"});
    edges.push_back({key,"is.from","source:"+r.source});
    // Explicit master membership is preserved in the compiled graph. Legacy
    // unassociated fragments retain their original provenance.
    if(r.source.rfind("record.",0)==0 && r.external.rfind("local:",0)==0){
        auto end=r.source.find('.',7);
        if(end!=std::string::npos){
            auto master="master:"+r.external;
            edges.push_back({key,"is.part.of",master});
            edges.push_back({key,"has.role","role:"+r.source.substr(end+1)});
            edges.push_back({master,"is.in","network:"+r.source.substr(7,end-7)});
        }
    }
}
void membership_edges(sqlite3 *db,std::vector<edge> &edges,int64_t id){
    statement s(db,"SELECT agent_id,fragment_seq FROM branch_members WHERE (?=0 OR fragment_seq=?) ORDER BY fragment_seq,agent_id");
    s.number(1,id);s.number(2,id);
    while(s.next())edges.push_back({fragment_key(s.integer(1)),"is.in","branch:"+std::to_string(s.integer(0))});
}
std::array<float,dim> lexical_vector(const std::string &input){
    // Stable byte-trigram FNV-1a hashing (ASCII fold). A transparent lexical
    // baseline; multilingual semantic providers will have different profiles.
    std::string s=" "+input+" ";
    for(char &c:s)if(c>='A'&&c<='Z')c=static_cast<char>(c-'A'+'a');
    std::array<float,dim> v{};
    for(size_t i=0;i+2<s.size();++i){
        uint32_t h=2166136261u;
        for(size_t j=i;j<i+3;++j)h=(h^static_cast<unsigned char>(s[j]))*16777619u;
        v[h&(dim-1)]+=1.f;
    }
    double norm=0;for(float x:v)norm+=static_cast<double>(x)*x;
    if(norm>0){float scale=static_cast<float>(1/std::sqrt(norm));for(float &x:v)x*=scale;}
    return v;
}
struct le_writer {
    std::ofstream &out; uint64_t count=0;
    void bytes(const void *p,size_t n){
        out.write(static_cast<const char*>(p),static_cast<std::streamsize>(n));count+=n;
    }
    void byte(uint8_t v){bytes(&v,1);}
    void u32(uint32_t v){uint8_t b[4];for(unsigned i=0;i<4;++i)b[i]=static_cast<uint8_t>(v>>(8*i));bytes(b,4);}
    void u64(uint64_t v){uint8_t b[8];for(unsigned i=0;i<8;++i)b[i]=static_cast<uint8_t>(v>>(8*i));bytes(b,8);}
    void str(const std::string &v){u64(v.size());bytes(v.data(),v.size());}
    void string_kv(const char *k,const std::string &v){str(k);u32(8);str(v);}
    void array_header(const char *k,size_t size,uint32_t type=8){str(k);u32(9);u32(type);u64(size);}
    void vector_row(const std::array<float,dim> &v){
        std::array<uint8_t,dim*4> b{};
        for(size_t i=0;i<dim;++i){
            uint32_t bits;std::memcpy(&bits,&v[i],sizeof(bits));
            for(unsigned j=0;j<4;++j)b[i*4+j]=static_cast<uint8_t>(bits>>(8*j));
        }
        bytes(b.data(),b.size());
    }
};
void write_gguf(std::ofstream &stream,const std::vector<row> &rows,
                const std::vector<edge> &edges,const std::vector<agent> &agents,int64_t rev,const char *language=nullptr){
    static_assert(sizeof(float)==4 && std::numeric_limits<float>::is_iec559,"GGUF requires IEEE f32");
    le_writer w{stream};
    w.byte('G');w.byte('G');w.byte('U');w.byte('F');w.u32(3);
    w.u64(1);w.u64(language?19:18);
    w.string_kv("general.architecture","roots-tree");
    w.str("general.alignment");w.u32(4);w.u32(32);
    w.string_kv("roots.vector.profile","roots.lexical.utf8-trigram.f32.v1");
    w.str("roots.tree.revision");w.u32(10);w.u64(static_cast<uint64_t>(rev));
    w.string_kv("roots.scope",language?"localized-private":"local-private");
    if(language)w.string_kv("roots.local.language",language);
    w.str("roots.format.version");w.u32(4);w.u32(2);
    w.array_header("roots.node.keys",rows.size());
    for(const auto &r:rows)w.str(fragment_key(r.id));
    // Lossless parallel arrays, in precisely the same order as node.embedding.
    // No tokenization, lossy conversion, or second copy of the original text.
    w.array_header("roots.node.text",rows.size());for(const auto &r:rows)w.str(r.body);
    w.array_header("roots.node.source",rows.size());for(const auto &r:rows)w.str(r.source);
    w.array_header("roots.node.external_id",rows.size());for(const auto &r:rows)w.str(r.external);
    w.array_header("roots.node.language",rows.size());for(const auto &r:rows)w.str(r.lang);
    w.array_header("roots.node.observed_ms",rows.size(),10);for(const auto &r:rows)w.u64(static_cast<uint64_t>(r.at));
    w.array_header("roots.branch.keys",agents.size());for(const auto &a:agents)w.str("branch:"+std::to_string(a.id));
    w.array_header("roots.branch.name",agents.size());for(const auto &a:agents)w.str(a.name);
    w.array_header("roots.branch.term",agents.size());for(const auto &a:agents)w.str(a.term);
    w.array_header("roots.edge.subject",edges.size());for(const auto &e:edges)w.str(e.subject);
    w.array_header("roots.edge.predicate",edges.size());for(const auto &e:edges)w.str(e.predicate);
    w.array_header("roots.edge.object",edges.size());for(const auto &e:edges)w.str(e.object);
    w.str("node.embedding");w.u32(2);w.u64(dim);w.u64(rows.size());w.u32(0);w.u64(0);
    while(w.count%32)w.byte(0);
    // Stream one fixed-size, little-endian tensor row at a time.
    // No full tensor allocation; identical adjacent text can reuse its vector.
    std::array<float,dim> previous{};
    const std::string *previous_text=nullptr;
    for(const auto &r:rows){
        if(!previous_text||*previous_text!=r.body)previous=lexical_vector(r.body);
        w.vector_row(previous);previous_text=&r.body;
    }
    stream.flush();if(!stream)throw std::runtime_error("GGUF write");
}
}
extern "C" {
uint32_t roots_tree_abi_version(void){return 1;}
const char *roots_tree_last_error(void){return last_error.c_str();}
roots_tree_result roots_tree_open(const char *path,roots_tree **out){
    if(out)*out=nullptr;
    return guard([&]{
        bounded(path,32768,true);require(out);
        auto t=std::make_unique<roots_tree>();
        check(sqlite3_open_v2(path,&t->db,SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_FULLMUTEX,nullptr));
        check(sqlite3_busy_timeout(t->db,5000));
        exec(t->db,"PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA secure_delete=ON;");
        auto version=scalar(t->db,"PRAGMA user_version");
        require(version==0||version==1);
        transaction tx(t->db);
        exec(t->db,"CREATE TABLE IF NOT EXISTS tree_state(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL);"
                   "INSERT OR IGNORE INTO tree_state VALUES(1,0);"
                   "CREATE TABLE IF NOT EXISTS fragments(seq INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,external_id TEXT NOT NULL,body TEXT NOT NULL,language TEXT NOT NULL,observed_ms INTEGER NOT NULL,UNIQUE(source,external_id,body));"
                   "CREATE TABLE IF NOT EXISTS agents(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,term TEXT NOT NULL);"
                   "CREATE TABLE IF NOT EXISTS branch_members(agent_id INTEGER NOT NULL REFERENCES agents(id) ON DELETE CASCADE,fragment_seq INTEGER NOT NULL REFERENCES fragments(seq) ON DELETE CASCADE,PRIMARY KEY(agent_id,fragment_seq));"
                   "CREATE INDEX IF NOT EXISTS branch_fragment ON branch_members(fragment_seq);"
                   "CREATE INDEX IF NOT EXISTS fragment_observed ON fragments(observed_ms DESC,seq DESC);"
                   "CREATE INDEX IF NOT EXISTS fragment_master ON fragments(external_id,seq) WHERE source GLOB 'record.*';"
                   "CREATE INDEX IF NOT EXISTS fragment_master_language ON fragments(external_id,language,seq) WHERE source GLOB 'record.*';"
                   "CREATE INDEX IF NOT EXISTS fragment_experience_member ON fragments(external_id,source,seq) WHERE source GLOB 'record.experience.*';"
                   "CREATE INDEX IF NOT EXISTS fragment_experience_text ON fragments(body) WHERE source='record.experience.body';"
                   "CREATE TABLE IF NOT EXISTS work_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,fragment INTEGER NOT NULL REFERENCES fragments(seq) ON DELETE CASCADE,operation TEXT NOT NULL,profile TEXT NOT NULL,priority INTEGER NOT NULL,deadline INTEGER NOT NULL,ready INTEGER NOT NULL DEFAULT 0,state INTEGER NOT NULL DEFAULT 0,worker TEXT NOT NULL DEFAULT '',lease_until INTEGER NOT NULL DEFAULT 0,token INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,output TEXT NOT NULL DEFAULT '',UNIQUE(fragment,operation,profile));"
                   "CREATE INDEX IF NOT EXISTS work_dispatch ON work_jobs(operation,state,priority DESC,deadline,id);"
                   "PRAGMA user_version=1;");
        tx.commit();*out=t.release();
    });
}
void roots_tree_close(roots_tree *t){delete t;}
roots_tree_result roots_tree_observe(roots_tree *t,const roots_observation *items,size_t n,size_t *inserted){
    if(inserted)*inserted=0;
    return guard([&]{
        require(t&&items&&inserted&&n>0&&n<=batch_max);
        for(size_t i=0;i<n;++i){const auto &o=items[i];bounded(o.source,256,true);bounded(o.external_id,512);bounded(o.body,16384,true);bounded(o.language,64);require(o.observed_ms>=0);}
        std::lock_guard<std::mutex> lock(t->mutex);transaction tx(t->db);
        size_t added=0;
        statement insert(t->db,"INSERT INTO fragments(source,external_id,body,language,observed_ms) SELECT ?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM fragments WHERE source=? AND external_id=? AND body=?)");
        statement match(t->db,"INSERT OR IGNORE INTO branch_members(agent_id,fragment_seq) SELECT id,? FROM agents WHERE instr(lower(?),lower(term))>0");
        for(size_t i=0;i<n;++i){
            const auto &o=items[i];insert.text(1,o.source);insert.text(2,o.external_id);insert.text(3,o.body);insert.text(4,o.language);insert.number(5,o.observed_ms);insert.text(6,o.source);insert.text(7,o.external_id);insert.text(8,o.body);insert.next();
            if(sqlite3_changes(t->db)>0){
                ++added;match.number(1,sqlite3_last_insert_rowid(t->db));match.text(2,o.body);match.next();match.reset();
            }
            insert.reset();
        }
        if(added)advance(t->db);
        tx.commit();*inserted=added;
    });
}
roots_tree_result roots_tree_query(roots_tree *t,const char *term,int64_t agent_id,int64_t before,uint32_t limit,roots_fragment_callback cb,void *user){
    return guard([&]{
        require(t&&cb&&agent_id>=0&&before>=0&&limit>0&&limit<=100);bounded(term,512);
        std::vector<row> rows;
        {
            std::lock_guard<std::mutex> lock(t->mutex);
            statement s(t->db,"SELECT f.seq,f.source,f.external_id,f.body,f.language,f.observed_ms FROM fragments f WHERE (?=0 OR f.seq<?) AND instr(lower(f.body),lower(?))>0 AND (?=0 OR EXISTS(SELECT 1 FROM branch_members b WHERE b.fragment_seq=f.seq AND b.agent_id=?)) ORDER BY f.seq DESC LIMIT ?");
            s.number(1,before);s.number(2,before);s.text(3,term);s.number(4,agent_id);s.number(5,agent_id);s.number(6,limit);
            while(s.next())rows.push_back(read_row(s));
        }
        for(const auto &r:rows){roots_fragment f{r.id,r.source.c_str(),r.external.c_str(),r.body.c_str(),r.lang.c_str(),r.at};if(cb(user,&f))break;}
    });
}
roots_tree_result roots_tree_records(roots_tree *t,const char *term,int64_t agent_id,
        int64_t before,uint32_t limit,roots_record_member_callback cb,void *user){
    return guard([&]{
        require(t&&cb&&agent_id>=0&&before>=0&&limit>0&&limit<=100);bounded(term,512);
        std::vector<std::pair<int64_t,row>> rows;
        {
            std::lock_guard<std::mutex> lock(t->mutex);
            // Match at member level, paginate at master level, then retrieve
            // every member. Never cut a record at a page boundary.
            statement s(t->db,
                "WITH members AS (SELECT * FROM fragments WHERE source GLOB 'record.*' AND external_id GLOB 'local:*'), "
                "masters AS (SELECT external_id,min(seq) AS master_seq FROM members GROUP BY external_id "
                "HAVING (?=0 OR min(seq)<?) AND max(CASE WHEN instr(lower(body),lower(?))>0 "
                "AND (?=0 OR EXISTS(SELECT 1 FROM branch_members b WHERE b.fragment_seq=members.seq AND b.agent_id=?)) "
                "THEN 1 ELSE 0 END)=1 ORDER BY master_seq DESC LIMIT ?) "
                "SELECT f.seq,f.source,f.external_id,f.body,f.language,f.observed_ms,m.master_seq "
                "FROM masters m JOIN members f ON f.external_id=m.external_id ORDER BY m.master_seq DESC,f.seq");
            s.number(1,before);s.number(2,before);s.text(3,term);
            s.number(4,agent_id);s.number(5,agent_id);s.number(6,limit);
            while(s.next())rows.emplace_back(s.integer(6),read_row(s));
        }
        for(const auto &entry:rows){const auto &r=entry.second;
            roots_fragment f{r.id,r.source.c_str(),r.external.c_str(),r.body.c_str(),r.lang.c_str(),r.at};
            if(cb(user,entry.first,&f))break;
        }
    });
}
roots_tree_result roots_tree_query_time(roots_tree *t,const char *term,int64_t agent_id,
        int64_t before_ms,int64_t before_seq,uint32_t limit,roots_fragment_callback cb,void *user){
    return guard([&]{
        require(t&&cb&&agent_id>=0&&before_ms>=0&&before_seq>=0&&limit>0&&limit<=100);
        require(before_seq!=0||before_ms==0);bounded(term,512);
        std::vector<row> rows;
        {
            std::lock_guard<std::mutex> lock(t->mutex);
            statement s(t->db,"SELECT f.seq,f.source,f.external_id,f.body,f.language,f.observed_ms FROM fragments f "
                "WHERE (?=0 OR f.observed_ms<? OR (f.observed_ms=? AND f.seq<?)) "
                "AND instr(lower(f.body),lower(?))>0 AND (?=0 OR EXISTS(SELECT 1 FROM branch_members b WHERE b.fragment_seq=f.seq AND b.agent_id=?)) "
                "ORDER BY f.observed_ms DESC,f.seq DESC LIMIT ?");
            s.number(1,before_seq);s.number(2,before_ms);s.number(3,before_ms);s.number(4,before_seq);
            s.text(5,term);s.number(6,agent_id);s.number(7,agent_id);s.number(8,limit);
            while(s.next())rows.push_back(read_row(s));
        }
        for(const auto &r:rows){roots_fragment f{r.id,r.source.c_str(),r.external.c_str(),r.body.c_str(),r.lang.c_str(),r.at};if(cb(user,&f))break;}
    });
}
roots_tree_result roots_tree_records_time(roots_tree *t,const char *term,int64_t agent_id,
        int64_t before_ms,int64_t before_seq,uint32_t limit,roots_record_member_callback cb,void *user){
    return guard([&]{
        require(t&&cb&&agent_id>=0&&before_ms>=0&&before_seq>=0&&limit>0&&limit<=100);
        require(before_seq!=0||before_ms==0);bounded(term,512);
        std::vector<std::pair<int64_t,row>> rows;
        {
            std::lock_guard<std::mutex> lock(t->mutex);
            statement s(t->db,
                "WITH members AS (SELECT * FROM fragments WHERE source GLOB 'record.*' AND external_id GLOB 'local:*'), "
                "masters AS (SELECT external_id,min(seq) AS master_seq,min(observed_ms) AS observed FROM members GROUP BY external_id "
                "HAVING (?=0 OR min(observed_ms)<? OR (min(observed_ms)=? AND min(seq)<?)) "
                "AND max(CASE WHEN instr(lower(body),lower(?))>0 "
                "AND (?=0 OR EXISTS(SELECT 1 FROM branch_members b WHERE b.fragment_seq=members.seq AND b.agent_id=?)) "
                "THEN 1 ELSE 0 END)=1 ORDER BY observed DESC,master_seq DESC LIMIT ?) "
                "SELECT f.seq,f.source,f.external_id,f.body,f.language,f.observed_ms,m.master_seq "
                "FROM masters m JOIN members f ON f.external_id=m.external_id ORDER BY m.observed DESC,m.master_seq DESC,f.seq");
            s.number(1,before_seq);s.number(2,before_ms);s.number(3,before_ms);s.number(4,before_seq);
            s.text(5,term);s.number(6,agent_id);s.number(7,agent_id);s.number(8,limit);
            while(s.next())rows.emplace_back(s.integer(6),read_row(s));
        }
        for(const auto &entry:rows){const auto &r=entry.second;
            roots_fragment f{r.id,r.source.c_str(),r.external.c_str(),r.body.c_str(),r.lang.c_str(),r.at};
            if(cb(user,entry.first,&f))break;
        }
    });
}
roots_tree_result roots_tree_screens_time(roots_tree *t,const char *term,int64_t agent_id,
        int64_t before_ms,int64_t before_seq,uint32_t limit,roots_record_member_callback cb,void *user){
    return guard([&]{
        require(t&&cb&&agent_id>=0&&before_ms>=0&&before_seq>=0&&limit>0&&limit<=100);
        require(before_seq!=0||before_ms==0);bounded(term,512);
        std::vector<std::pair<int64_t,row>> rows;
        {
            std::lock_guard<std::mutex> lock(t->mutex);
            // Aggregate each observation once instead of correlated scans of
            // the entire Experience namespace for every body. The dedicated
            // partial index matches the exact GLOB predicate used here.
            // A filter selects the complete screen, not just matching lines.
            statement s(t->db,
                "WITH observations AS (SELECT external_id,"
                "min(CASE WHEN source='record.experience.body' THEN seq END) AS event_seq,"
                "max(CASE WHEN source='record.experience.body' THEN observed_ms END) AS observed_ms,"
                "min(CASE WHEN source='record.experience.screen' THEN body END) AS screen,"
                "min(CASE WHEN source='record.experience.parent' THEN body END) AS parent,"
                "max(CASE WHEN (?1='' OR instr(lower(body),lower(?1))>0) AND "
                "(?2=0 OR EXISTS(SELECT 1 FROM branch_members m WHERE m.fragment_seq=f.seq AND m.agent_id=?2)) THEN 1 ELSE 0 END) AS matched "
                "FROM fragments f WHERE source GLOB 'record.experience.*' AND external_id GLOB 'local:experience:*' "
                "GROUP BY external_id HAVING event_seq IS NOT NULL AND "
                "max(CASE WHEN source='record.experience.kind' AND body='observation' THEN 1 ELSE 0 END)=1),"
                "events AS (SELECT external_id,event_seq,observed_ms,"
                "COALESCE(screen,parent||':legacy-screen:'||observed_ms,external_id) AS screen_key,matched FROM observations),"
                "screens AS (SELECT screen_key,min(event_seq) AS screen_seq,max(observed_ms) AS observed FROM events GROUP BY screen_key "
                "HAVING max(matched)=1 AND (?3=0 OR max(observed_ms)<?4 OR (max(observed_ms)=?4 AND min(event_seq)<?3)) "
                "ORDER BY observed DESC,screen_seq DESC LIMIT ?5) "
                "SELECT f.seq,f.source,f.external_id,f.body,f.language,f.observed_ms,g.screen_seq "
                "FROM screens g JOIN events e ON e.screen_key=g.screen_key "
                "JOIN fragments f ON f.external_id=e.external_id AND f.source GLOB 'record.experience.*' "
                "ORDER BY g.observed DESC,g.screen_seq DESC,e.event_seq,f.seq");
            s.text(1,term);s.number(2,agent_id);s.number(3,before_seq);s.number(4,before_ms);s.number(5,limit);
            while(s.next())rows.emplace_back(s.integer(6),read_row(s));
        }
        for(const auto &entry:rows){const auto &r=entry.second;
            roots_fragment f{r.id,r.source.c_str(),r.external.c_str(),r.body.c_str(),r.lang.c_str(),r.at};
            if(cb(user,entry.first,&f))break;
        }
    });
}
roots_tree_result roots_tree_fragment_weights(roots_tree *t,int64_t sequence,float *weights,size_t count){
    return guard([&]{
        require(t&&sequence>0&&weights&&count==dim);
        std::string body;
        {
            std::lock_guard<std::mutex> lock(t->mutex);
            statement s(t->db,"SELECT body FROM fragments WHERE seq=?");
            s.number(1,sequence);require(s.next());body=s.str(0);
        }
        const auto values=lexical_vector(body);
        std::copy(values.begin(),values.end(),weights);
    });
}
roots_tree_result roots_tree_text_occurrences(roots_tree *t,int64_t sequence,int64_t *out){
    if(out)*out=0;
    return guard([&]{
        require(t&&out&&sequence>0);
        std::lock_guard<std::mutex> lock(t->mutex);
        statement s(t->db,
            "SELECT count(*) FROM fragments f WHERE f.source='record.experience.body' "
            "AND f.body=(SELECT body FROM fragments WHERE seq=? AND source='record.experience.body') "
            "AND EXISTS(SELECT 1 FROM fragments k WHERE k.source='record.experience.kind' "
            "AND k.external_id=f.external_id AND k.body='observation')");
        s.number(1,sequence);require(s.next());*out=s.integer(0);
    });
}
roots_tree_result roots_tree_backup(roots_tree *t,const char *path,int64_t *out){
    if(out)*out=0;
    return guard([&]{
        require(t&&out);bounded(path,32768,true);
        const auto dest=std::filesystem::u8path(path);
        require(!std::filesystem::exists(dest));
        try{
            sqlite3 *raw=nullptr;
            const int opened=sqlite3_open_v2(path,&raw,SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_FULLMUTEX,nullptr);
            std::unique_ptr<sqlite3,decltype(&sqlite3_close)> copy(raw,&sqlite3_close);
            check(opened);
            {
                std::lock_guard<std::mutex> lock(t->mutex);
                sqlite3_backup *backup=sqlite3_backup_init(copy.get(),"main",t->db,"main");
                if(!backup)throw std::runtime_error("Could not initialize SQLite backup");
                int rc=SQLITE_BUSY;
                for(int attempt=0;attempt<40;++attempt){
                    rc=sqlite3_backup_step(backup,-1);
                    if(rc!=SQLITE_BUSY&&rc!=SQLITE_LOCKED)break;
                    sqlite3_sleep(50);
                }
                const int finished=sqlite3_backup_finish(backup);
                if(rc!=SQLITE_DONE)throw std::runtime_error("SQLite backup did not complete");
                check(finished);
            }
            exec(copy.get(),"PRAGMA journal_mode=DELETE");
            {
                statement integrity(copy.get(),"PRAGMA quick_check");
                if(!integrity.next()||integrity.str(0)!="ok"||integrity.next())
                    throw std::runtime_error("SQLite backup integrity check failed");
            }
            *out=revision_of(copy.get());
            copy.reset();
        }catch(...){
            std::error_code ec;std::filesystem::remove(dest,ec);
            std::filesystem::remove(std::filesystem::u8path(std::string(path)+"-wal"),ec);
            std::filesystem::remove(std::filesystem::u8path(std::string(path)+"-shm"),ec);
            std::filesystem::remove(std::filesystem::u8path(std::string(path)+"-journal"),ec);
            throw;
        }
    });
}
roots_tree_result roots_tree_count(roots_tree *t,int64_t *out){return guard([&]{require(t&&out);std::lock_guard<std::mutex> lock(t->mutex);*out=scalar(t->db,"SELECT count(*) FROM fragments");});}
roots_tree_result roots_tree_revision(roots_tree *t,int64_t *out){return guard([&]{require(t&&out);std::lock_guard<std::mutex> lock(t->mutex);*out=revision_of(t->db);});}
roots_tree_result roots_tree_agent_create(roots_tree *t,const char *name,const char *term,int64_t *id){
    if(id)*id=0;
    return guard([&]{
        require(t&&id);bounded(name,160,true);bounded(term,512,true);
        std::lock_guard<std::mutex> lock(t->mutex);transaction tx(t->db);
        if(scalar(t->db,"SELECT count(*) FROM agents")>=static_cast<int64_t>(max_agents))throw quota("agents");
        statement insert(t->db,"INSERT INTO agents(name,term) VALUES(?,?)");insert.text(1,name);insert.text(2,term);insert.next();
        auto a=sqlite3_last_insert_rowid(t->db);
        statement backfill(t->db,"INSERT INTO branch_members SELECT ?,seq FROM fragments WHERE instr(lower(body),lower(?))>0");backfill.number(1,a);backfill.text(2,term);backfill.next();
        advance(t->db);tx.commit();*id=a;
    });
}
roots_tree_result roots_tree_agent_list(roots_tree *t,roots_agent_callback cb,void *user){
    return guard([&]{
        require(t&&cb);std::vector<agent> agents;
        {std::lock_guard<std::mutex> lock(t->mutex);statement s(t->db,"SELECT id,name,term FROM agents ORDER BY id");while(s.next())agents.push_back({s.integer(0),s.str(1),s.str(2)});}
        for(const auto &a:agents)if(cb(user,a.id,a.name.c_str(),a.term.c_str()))break;
    });
}
roots_tree_result roots_tree_agent_remove(roots_tree *t,int64_t id){
    return guard([&]{require(t&&id>0);std::lock_guard<std::mutex> lock(t->mutex);transaction tx(t->db);statement s(t->db,"DELETE FROM agents WHERE id=?");s.number(1,id);s.next();if(sqlite3_changes(t->db))advance(t->db);tx.commit();});
}
roots_tree_result roots_tree_predicate(const char *const *parts,size_t n,char *out,size_t cap){
    return guard([&]{
        require(parts&&out&&n>0&&n<=8);std::string key;
        for(size_t i=0;i<n;++i){
            bounded(parts[i],32,true);if(i)key+='.';
            for(size_t j=0;parts[i][j];++j){
                char c=parts[i][j];if(c>='A'&&c<='Z')c=static_cast<char>(c-'A'+'a');
                bool letter=c>='a'&&c<='z';
                require(letter||(j>0&&((c>='0'&&c<='9')||c=='_'||c=='-')));
                key+=c;
            }
        }
        if(key.size()+1>cap)throw quota("predicate output");
        std::memcpy(out,key.c_str(),key.size()+1);
    });
}
roots_tree_result roots_tree_relations(roots_tree *t,int64_t id,roots_relation_callback cb,void *user){
    return guard([&]{
        require(t&&id>0&&cb);std::vector<edge> edges;
        {std::lock_guard<std::mutex> lock(t->mutex);statement s(t->db,"SELECT seq,source,external_id,body,language,observed_ms FROM fragments WHERE seq=?");s.number(1,id);if(s.next()){add_edges(edges,read_row(s));membership_edges(t->db,edges,id);}}
        for(const auto &e:edges)if(cb(user,e.subject.c_str(),e.predicate.c_str(),e.object.c_str()))break;
    });
}
static roots_tree_result compile_tree(roots_tree *t,const char *path,int64_t *out,const char *language){
    if(out)*out=0;
    return guard([&]{
        require(t&&out);bounded(path,32768,true);if(language)bounded(language,64,true);
        std::vector<row> rows;std::vector<edge> edges;std::vector<agent> agents;int64_t rev;
        {
            std::lock_guard<std::mutex> lock(t->mutex);
            // Snapshot is internally consistent even if another process writes.
            exec(t->db,"BEGIN");
            try{
                rev=revision_of(t->db);
                // One preferred local-language content node per grouped master.
                statement s(t->db,language?
                    "SELECT seq,source,external_id,body,language,observed_ms FROM fragments f "
                    "WHERE source GLOB 'record.*' AND external_id GLOB 'local:*' AND language=? "
                    "AND (source GLOB 'record.*.understanding' OR "
                    "(source GLOB 'record.*.body' AND NOT EXISTS(SELECT 1 FROM fragments g WHERE g.external_id=f.external_id AND g.language=f.language AND g.source GLOB 'record.*' AND (g.source GLOB 'record.*.translation' OR g.source GLOB 'record.*.localized'))) OR "
                    "((source GLOB 'record.*.translation' OR source GLOB 'record.*.localized') AND seq=(SELECT max(g.seq) FROM fragments g WHERE g.external_id=f.external_id AND g.language=f.language AND g.source GLOB 'record.*' "
                    "AND (g.source GLOB 'record.*.translation' OR g.source GLOB 'record.*.localized')))) ORDER BY seq DESC LIMIT 10000":
                    "SELECT seq,source,external_id,body,language,observed_ms FROM fragments ORDER BY seq");
                if(language)s.text(1,language);
                if(language)rows.reserve(10000);
                while(s.next())rows.push_back(read_row(s));
                if(language){
                    // Bound active phone coordination, not durable capture.
                    std::reverse(rows.begin(),rows.end());
                    std::unordered_set<std::string> active;
                    for(const auto &r:rows)active.insert(r.external);
                    // Preserve record-level branch membership and wanted state,
                    // even when the matching original was excluded. No private text.
                    statement branch(t->db,"SELECT DISTINCT f.external_id,b.agent_id FROM branch_members b JOIN fragments f ON f.seq=b.fragment_seq WHERE f.source GLOB 'record.*' AND f.external_id GLOB 'local:*'");
                    while(branch.next())if(active.count(branch.str(0)))edges.push_back({"master:"+branch.str(0),"is.in","branch:"+std::to_string(branch.integer(1))});
                    statement wants(t->db,"SELECT DISTINCT external_id FROM fragments WHERE source GLOB 'record.*.want' AND external_id GLOB 'local:*'");
                    while(wants.next())if(active.count(wants.str(0)))edges.push_back({"master:"+wants.str(0),"has.state","state:wanted"});
                }else membership_edges(t->db,edges,0);
                statement a(t->db,"SELECT id,name,term FROM agents ORDER BY id");
                while(a.next())agents.push_back({a.integer(0),a.str(1),a.str(2)});
                exec(t->db,"COMMIT");
            }catch(...){sqlite3_exec(t->db,"ROLLBACK",nullptr,nullptr,nullptr);throw;}
        }
        if(language&&rows.empty()){*out=rev;return;}
        require(!rows.empty());
        if(language){
            std::unordered_set<std::string> keys;for(const auto &r:rows){keys.insert(fragment_key(r.id));keys.insert("master:"+r.external);}
            edges.erase(std::remove_if(edges.begin(),edges.end(),[&](const edge &e){return !keys.count(e.subject);}),edges.end());
            // Preserve branch identities and relation edges without copying
            // private terms/names into the localized projection.
            for(auto &a:agents){a.name.clear();a.term.clear();}
        }
        // Build and deduplicate derived edges outside the writer mutex.
        // Multiple members of the same master emit the same network edge.
        edges.reserve(edges.size()+rows.size()*5);
        for(const auto &r:rows)add_edges(edges,r);
        auto key=[](const edge &e){return std::tie(e.subject,e.predicate,e.object);};
        std::sort(edges.begin(),edges.end(),[&](const edge &a,const edge &b){return key(a)<key(b);});
        edges.erase(std::unique(edges.begin(),edges.end(),[&](const edge &a,const edge &b){return key(a)==key(b);}),edges.end());
        auto dest=std::filesystem::u8path(path);
        // This API takes a new path reserved by the application. Never overwrite
        // an existing file. Single application compiler owns that directory.
        require(!std::filesystem::exists(dest));
        try{
            std::ofstream file(dest,std::ios::binary|std::ios::out);
            if(!file)throw std::runtime_error("open snapshot");
            write_gguf(file,rows,edges,agents,rev,language);file.close();if(!file)throw std::runtime_error("close snapshot");
            *out=rev;
        }catch(...){std::error_code ec;std::filesystem::remove(dest,ec);throw;}
    });
}

roots_tree_result roots_tree_compile(roots_tree *t,const char *path,int64_t *out){return compile_tree(t,path,out,nullptr);}
roots_tree_result roots_tree_compile_localized(roots_tree *t,const char *path,const char *language,int64_t *out){
    if(!language||!*language)return ROOTS_TREE_INVALID;
    return compile_tree(t,path,out,language);
}

roots_tree_result roots_tree_clear(roots_tree *t){
    return guard([&]{require(t);std::lock_guard<std::mutex> lock(t->mutex);transaction tx(t->db);exec(t->db,"DELETE FROM agents; DELETE FROM fragments;");advance(t->db);tx.commit();});
}
}

namespace {
struct work_row {
    int64_t id=0, fragment=0, priority=0, deadline=0, until=0, token=0, attempts=0, ready=0;
    int32_t state=0;
    std::string operation,profile,worker,output;
    void deliver(roots_work_callback cb,void *user) const {
        if(!id)return;
        roots_work w{id,fragment,priority,deadline,until,token,attempts,ready,state,
            operation.c_str(),profile.c_str(),worker.c_str(),output.c_str()};
        cb(user,&w);
    }
};
work_row work_get(sqlite3 *db,int64_t id) {
    statement q(db,"SELECT id,fragment,priority,deadline,lease_until,token,attempts,ready,state,operation,profile,worker,output FROM work_jobs WHERE id=?");
    q.number(1,id);if(!q.next())return {};
    return {q.integer(0),q.integer(1),q.integer(2),q.integer(3),q.integer(4),q.integer(5),q.integer(6),q.integer(7),
        static_cast<int32_t>(q.integer(8)),q.str(9),q.str(10),q.str(11),q.str(12)};
}
bool lease_valid(int64_t now,int64_t duration){
    return now>=0&&duration>0&&duration<=3600000&&now<=std::numeric_limits<int64_t>::max()-duration;
}
}
extern "C" {
roots_tree_result roots_work_request(roots_tree *t,int64_t fragment,const char *operation,const char *profile,int64_t priority,int64_t deadline,int64_t *id){
    if(id)*id=0;
    return guard([&]{
        require(t&&id&&fragment>0&&priority>=0&&priority<=1000&&deadline>=0);
        bounded(operation,64,true);bounded(profile,256,true);
        std::lock_guard<std::mutex> lock(t->mutex);transaction tx(t->db);
        statement exists(t->db,"SELECT 1 FROM fragments WHERE seq=?");exists.number(1,fragment);require(exists.next());
        statement existing(t->db,"SELECT id FROM work_jobs WHERE fragment=? AND operation=? AND profile=?");
        existing.number(1,fragment);existing.text(2,operation);existing.text(3,profile);
        if(existing.next()) {
            *id=existing.integer(0);
            statement u(t->db,"UPDATE work_jobs SET priority=max(priority,?),deadline=CASE WHEN deadline=0 THEN ? WHEN ?=0 THEN deadline ELSE min(deadline,?) END WHERE id=? AND state IN (0,1)");
            u.number(1,priority);u.number(2,deadline);u.number(3,deadline);u.number(4,deadline);u.number(5,*id);u.next();
        }else{
            if(scalar(t->db,"SELECT count(*) FROM work_jobs")>=40000)throw quota("work capacity");
            statement ins(t->db,"INSERT INTO work_jobs(fragment,operation,profile,priority,deadline) VALUES(?,?,?,?,?)");
            ins.number(1,fragment);ins.text(2,operation);ins.text(3,profile);ins.number(4,priority);ins.number(5,deadline);ins.next();
            *id=sqlite3_last_insert_rowid(t->db);
        }
        tx.commit();
    });
}
roots_tree_result roots_work_read(roots_tree *t,int64_t id,roots_work_callback cb,void *user){
    return guard([&]{require(t&&id>0&&cb);work_row row;{std::lock_guard<std::mutex> lock(t->mutex);row=work_get(t->db,id);}row.deliver(cb,user);});
}
roots_tree_result roots_work_claim(roots_tree *t,const char *operation,const char *worker,int64_t now,int64_t duration,roots_work_callback cb,void *user){
    return guard([&]{
        require(t&&cb&&lease_valid(now,duration));bounded(operation,64,true);bounded(worker,128,true);work_row row;
        {
            std::lock_guard<std::mutex> lock(t->mutex);transaction tx(t->db);
            statement q(t->db,"SELECT id FROM work_jobs WHERE operation=? AND ((state=0 AND ready<=?) OR (state=1 AND lease_until<=?)) ORDER BY priority DESC,CASE WHEN deadline=0 THEN 9223372036854775807 ELSE deadline END,id LIMIT 1");
            q.text(1,operation);q.number(2,now);q.number(3,now);
            if(q.next()){
                auto id=q.integer(0);
                statement u(t->db,"UPDATE work_jobs SET state=1,worker=?,lease_until=?,token=token+1,attempts=attempts+1 WHERE id=?");
                u.text(1,worker);u.number(2,now+duration);u.number(3,id);u.next();row=work_get(t->db,id);
            }
            tx.commit();
        }
        row.deliver(cb,user);
    });
}
roots_tree_result roots_work_finish(roots_tree *t,int64_t id,int64_t token,const char *worker,int64_t now,int32_t state,const char *output,int64_t retry){
    bool changed=false;
    auto r=guard([&]{
        require(t&&id>0&&token>0&&now>=0&&(state==ROOTS_WORK_PENDING||state==ROOTS_WORK_COMPLETED||state==ROOTS_WORK_FAILED));
        require(state!=ROOTS_WORK_PENDING||retry>=now);bounded(worker,128,true);bounded(output,32768);
        std::lock_guard<std::mutex> lock(t->mutex);
        statement u(t->db,"UPDATE work_jobs SET state=?,output=?,ready=?,worker='',lease_until=0 WHERE id=? AND token=? AND worker=? AND state=1 AND lease_until>?");
        u.number(1,state);u.text(2,state==ROOTS_WORK_PENDING?"":output);u.number(3,state==ROOTS_WORK_PENDING?retry:0);
        u.number(4,id);u.number(5,token);u.text(6,worker);u.number(7,now);u.next();changed=sqlite3_changes(t->db)==1;
    });
    return r==ROOTS_TREE_OK&&!changed?ROOTS_TREE_STALE:r;
}
roots_tree_result roots_work_renew(roots_tree *t,int64_t id,int64_t token,const char *worker,int64_t now,int64_t duration){
    bool changed=false;
    auto r=guard([&]{require(t&&id>0&&token>0&&lease_valid(now,duration));bounded(worker,128,true);
        std::lock_guard<std::mutex> lock(t->mutex);
        statement u(t->db,"UPDATE work_jobs SET lease_until=max(lease_until,?) WHERE id=? AND token=? AND worker=? AND state=1 AND lease_until>?");
        u.number(1,now+duration);u.number(2,id);u.number(3,token);u.text(4,worker);u.number(5,now);u.next();changed=sqlite3_changes(t->db)==1;
    });
    return r==ROOTS_TREE_OK&&!changed?ROOTS_TREE_STALE:r;
}
roots_tree_result roots_work_cancel(roots_tree *t,int64_t id){
    bool changed=false;auto r=guard([&]{require(t&&id>0);std::lock_guard<std::mutex> lock(t->mutex);
        statement u(t->db,"UPDATE work_jobs SET state=4,worker='',lease_until=0,output='' WHERE id=? AND state IN (0,1)");
        u.number(1,id);u.next();changed=sqlite3_changes(t->db)==1;
    });return r==ROOTS_TREE_OK&&!changed?ROOTS_TREE_STALE:r;
}
}
