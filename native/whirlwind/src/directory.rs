//! Public External Mesh only. Topic/provider records are untrusted hints;
//! node cards are accepted only with a verified, unexpired owner signature.
use super::*;
use libp2p::kad::{self, GetProvidersOk, GetRecordOk, QueryId, QueryResult, Record, RecordKey, Quorum};
use serde::{Deserialize,Serialize};
use std::{collections::{BTreeMap,BTreeSet,HashMap,HashSet},time::{SystemTime,UNIX_EPOCH}};
use tokio::time::{Instant,timeout_at};

const VERSION:u8=1;
const SHARDS:u8=8;
const MAX_NODES:usize=48;
const MAX_TOPICS:usize=12;
const TTL:u64=86400;
const MAX_RECORD:usize=8192;
const DOMAIN:&[u8]=b"roots.external.node.v1\0";

#[derive(Debug,Clone,Serialize,Deserialize,PartialEq,Eq)]
#[serde(deny_unknown_fields)]
pub struct NodeCard{
    pub version:u8,
    pub peer_id:String,
    pub display_name:String,
    pub topics:Vec<String>,
    pub revision:u64,
    pub expires:u64,
    // Directory-only until a node really exposes an authenticated Axon exchange service.
    pub capabilities:Vec<String>,
}
#[derive(Debug,Clone,Serialize,Deserialize)]
#[serde(deny_unknown_fields)]
struct SignedCard{body:String,public_key:String,signature:String}
#[derive(Debug,Serialize,Deserialize)]
pub struct SearchResults{pub nodes:Vec<NodeCard>,pub partial:bool}
#[derive(Debug,Clone)]
pub struct Publication{pub name:String,pub topics:Vec<String>}

fn now()->Result<u64>{Ok(SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs())}
fn topic(raw:&str)->Result<String>{
    let s=raw.trim().to_ascii_lowercase();
    if s.is_empty()||s.len()>48||!s.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'-'||c==b'_'){bail!("Use an exact topic tag of 1–48 ASCII letters, digits, hyphens or underscores")}
    Ok(s)
}
fn validate(card:&NodeCard,at:u64)->Result<()>{
    if card.version!=VERSION||card.display_name.trim().is_empty()||card.display_name.len()>120||card.display_name.chars().any(char::is_control)
        ||card.revision>at.saturating_add(300)||card.expires<=at||card.expires>card.revision.saturating_add(TTL)
        ||card.topics.len()>MAX_TOPICS||card.capabilities!=vec!["directory".to_owned()]{bail!("Invalid or expired public node card")}
    let normalized=card.topics.iter().map(|v|topic(v)).collect::<Result<BTreeSet<_>>>()?;
    if normalized.into_iter().collect::<Vec<_>>()!=card.topics{bail!("Noncanonical topics")}
    Ok(())
}
fn sign(key:&identity::Keypair,p:&Publication,at:u64)->Result<(NodeCard,Vec<u8>)>{
    let topics=p.topics.iter().map(|x|topic(x)).collect::<Result<BTreeSet<_>>>()?.into_iter().collect();
    let card=NodeCard{version:VERSION,peer_id:key.public().to_peer_id().to_string(),display_name:p.name.trim().to_owned(),topics,
        revision:at,expires:at+TTL,capabilities:vec!["directory".to_owned()]};
    validate(&card,at)?;
    let bytes=serde_json::to_vec(&card)?;
    let mut signed=DOMAIN.to_vec();signed.extend(&bytes);
    let record=SignedCard{body:hex::encode(bytes),public_key:hex::encode(key.public().encode_protobuf()),signature:hex::encode(key.sign(&signed)?)};
    let data=serde_json::to_vec(&record)?;
    if data.len()>MAX_RECORD{bail!("Node card too large")}
    Ok((card,data))
}
fn verify(data:&[u8],expected:PeerId,at:u64)->Result<NodeCard>{
    if data.len()>MAX_RECORD{bail!("Oversized node card")}
    let record:SignedCard=serde_json::from_slice(data)?;
    let key=identity::PublicKey::try_decode_protobuf(&hex::decode(record.public_key)?)?;
    if key.to_peer_id()!=expected{bail!("Node card owner mismatch")}
    let bytes=hex::decode(record.body)?;
    let mut signed=DOMAIN.to_vec();signed.extend(&bytes);
    if !key.verify(&signed,&hex::decode(record.signature)?){bail!("Invalid node signature")}
    let card:NodeCard=serde_json::from_slice(&bytes)?;
    if card.peer_id!=expected.to_string()||serde_json::to_vec(&card)?!=bytes{bail!("Node card identity or encoding mismatch")}
    validate(&card,at)?;Ok(card)
}
fn node_key(peer:&PeerId)->RecordKey{RecordKey::new(&format!("roots/external/node/1/{peer}"))}
fn topic_key(topic:&str)->RecordKey{RecordKey::new(&format!("roots/external/topic/1/{topic}"))}
fn shard_key(n:u8)->RecordKey{RecordKey::new(&format!("roots/external/browse/1/{n}"))}
fn shard(peer:&PeerId)->u8{Sha256::digest(peer.to_bytes())[0]%SHARDS}

#[derive(NetworkBehaviour)]
struct DirectoryBehaviour{
    kad:kad::Behaviour<kad::store::MemoryStore>,
    identify:libp2p::identify::Behaviour,
}
async fn create(seed:&[u8;32],seeds:Vec<Multiaddr>,server:bool)->Result<Swarm<DirectoryBehaviour>>{
    if seeds.len()>8{bail!("Too many bootstrap addresses")}
    let key=p2p_key(seed,true)?;
    let mut swarm=SwarmBuilder::with_existing_identity(key).with_tokio()
        .with_tcp(libp2p::tcp::Config::default(),libp2p::noise::Config::new,libp2p::yamux::Config::default)?
        .with_quic().with_dns()?.with_behaviour(|key|{
            let peer=key.public().to_peer_id();
            let mut config=kad::Config::new(EXTERNAL_KAD_PROTOCOL);
            config.set_query_timeout(Duration::from_secs(12));
            DirectoryBehaviour{
                kad:kad::Behaviour::with_config(peer,kad::store::MemoryStore::new(peer),config),
                identify:libp2p::identify::Behaviour::new(libp2p::identify::Config::new("/roots/external/identify/1".into(),key.public())),
            }
        })?.build();
    swarm.behaviour_mut().kad.set_mode(Some(if server{kad::Mode::Server}else{kad::Mode::Client}));
    if server{
        let listen=std::env::var("ROOTS_EXTERNAL_LISTEN").unwrap_or_else(|_|"/ip4/0.0.0.0/tcp/0".into());
        swarm.listen_on(listen.parse()?)?;
        let _=swarm.listen_on("/ip6/::/tcp/0".parse()?);
    }
    for addr in seeds{
        let Some(Protocol::P2p(id))=addr.iter().last() else{bail!("External bootstrap requires a peer ID")};
        if &id==swarm.local_peer_id(){bail!("Bootstrap points to self")}
        swarm.behaviour_mut().kad.add_address(&id,addr.clone());let _=swarm.dial(addr);
    }
    let _=swarm.behaviour_mut().kad.bootstrap();
    Ok(swarm)
}
fn observe(swarm:&mut Swarm<DirectoryBehaviour>,event:&SwarmEvent<DirectoryBehaviourEvent>){
    if let SwarmEvent::Behaviour(DirectoryBehaviourEvent::Identify(libp2p::identify::Event::Received{peer_id,info,..}))=event{
        if !info.protocols.iter().any(|p|p==&EXTERNAL_KAD_PROTOCOL){return}
        for addr in info.listen_addrs.iter().take(8){
            if addr.to_string().len()<=512{swarm.behaviour_mut().kad.add_address(peer_id,addr.clone());}
        }
    }
}
fn announce(swarm:&mut Swarm<DirectoryBehaviour>,key:&identity::Keypair,p:&Publication)->Result<()>{
    let (card,record)=sign(key,p,now()?)?;
    let peer=*swarm.local_peer_id();
    swarm.behaviour_mut().kad.put_record(Record::new(node_key(&peer),record),Quorum::One)?;
    swarm.behaviour_mut().kad.start_providing(shard_key(shard(&peer)))?;
    for tag in &card.topics{swarm.behaviour_mut().kad.start_providing(topic_key(tag))?;}
    println!("External directory card prepared: {}",card.peer_id);
    Ok(())
}
pub async fn serve(seed:&[u8;32],seeds:Vec<Multiaddr>,publication:Option<Publication>)->Result<()>{
    let key=p2p_key(seed,true)?;
    // A publisher needs at least one reachable routing seed to advertise to others.
    if publication.is_some()&&seeds.is_empty(){bail!("Publication requires a reachable external bootstrap node")}
    let mut swarm=create(seed,seeds,true).await?;
    println!("External directory peer: {}",swarm.local_peer_id());
    let mut next=tokio::time::interval(Duration::from_secs(1800));
    next.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut announced=false;
    loop{tokio::select!{
        event=swarm.select_next_some()=>{
            observe(&mut swarm,&event);
            match event{
                SwarmEvent::NewListenAddr{address,..}=>println!("External DHT route: {address}/p2p/{}",swarm.local_peer_id()),
                SwarmEvent::ConnectionEstablished{..} if !announced=>{
                    if let Some(p)=&publication{announce(&mut swarm,&key,p)?;announced=true;}
                }
                SwarmEvent::Behaviour(DirectoryBehaviourEvent::Kad(kad::Event::OutboundQueryProgressed{result:QueryResult::PutRecord(Err(_)),..}))=>{announced=false;eprintln!("Directory publication not confirmed; will retry");}
                _=>{}
            }
        }
        _=next.tick()=>{if let Some(p)=&publication{
            if swarm.connected_peers().next().is_some(){announce(&mut swarm,&key,p)?;announced=true;}
        }}
        _=tokio::signal::ctrl_c()=>return Ok(()),
    }}
}
/// Browse is sampled, not a global enumeration. Exact topic search verifies
/// provider advertisements against the actual signed card before presenting it.
pub async fn search(seed:&[u8;32],seeds:Vec<Multiaddr>,query:&str)->Result<SearchResults>{
    if seeds.is_empty(){bail!("Configure an external bootstrap node")}
    let term=if query.trim().is_empty()||query.trim()=="*"{None}else{Some(topic(query)?)};
    let mut swarm=create(seed,seeds,false).await?;
    let keys=match &term{Some(t)=>vec![topic_key(t)],None=>(0..SHARDS).map(shard_key).collect()};
    let mut queries:HashSet<QueryId>=keys.into_iter().map(|k|swarm.behaviour_mut().kad.get_providers(k)).collect();
    let until=Instant::now()+Duration::from_secs(14);
    let mut peers=BTreeSet::new();let mut partial=false;
    while !queries.is_empty()&&peers.len()<MAX_NODES{
        let Ok(event)=timeout_at(until,swarm.select_next_some()).await else{partial=true;break};
        observe(&mut swarm,&event);
        if let SwarmEvent::Behaviour(DirectoryBehaviourEvent::Kad(kad::Event::OutboundQueryProgressed{id,result,step,..}))=event{
            if !queries.contains(&id){continue}
            if let QueryResult::GetProviders(value)=result{
                match value{
                    Ok(GetProvidersOk::FoundProviders{providers,..})=>for peer in providers{
                        if peers.len()<MAX_NODES&& &peer!=swarm.local_peer_id(){peers.insert(peer);}
                    },
                    Err(_)=>partial=true,
                    _=>{}
                }
                if step.last{queries.remove(&id);}
            }
        }
    }
    if !queries.is_empty()||peers.len()>=MAX_NODES{partial=true;}
    // Fetch only a bounded set of candidate cards, in small batches.
    let mut verified:BTreeMap<String,NodeCard>=BTreeMap::new();
    let until=Instant::now()+Duration::from_secs(16);
    let candidates:Vec<_>=peers.into_iter().collect();
    for batch in candidates.chunks(8){
        let mut pending:HashMap<QueryId,PeerId>=HashMap::new();
        for peer in batch{
            let id=swarm.behaviour_mut().kad.get_record(node_key(peer));
            pending.insert(id,*peer);
        }
        while !pending.is_empty(){
            let Ok(event)=timeout_at(until,swarm.select_next_some()).await else{partial=true;break};
            observe(&mut swarm,&event);
            if let SwarmEvent::Behaviour(DirectoryBehaviourEvent::Kad(kad::Event::OutboundQueryProgressed{id,result,step,..}))=event{
                let Some(peer)=pending.get(&id).copied() else{continue};
                if let QueryResult::GetRecord(value)=result{
                    match value{
                        Ok(GetRecordOk::FoundRecord(found))=>{
                            if found.record.key==node_key(&peer){
                                if let Ok(card)=verify(&found.record.value,peer,now()?){
                                    if term.as_ref().map_or(true,|t|card.topics.contains(t)){
                                        let replace=verified.get(&card.peer_id).map_or(true,|old|card.revision>old.revision);
                                        if replace{verified.insert(card.peer_id.clone(),card);}
                                    }
                                }
                            }
                        },
                        Err(_)=>partial=true,
                        _=>{}
                    }
                    if step.last{pending.remove(&id);}
                }
            }
        }
        if Instant::now()>=until{partial=true;break}
    }
    let mut nodes:Vec<_>=verified.into_values().collect();
    nodes.sort_by(|a,b|a.display_name.to_lowercase().cmp(&b.display_name.to_lowercase()).then(a.peer_id.cmp(&b.peer_id)));
    Ok(SearchResults{nodes,partial})
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]fn signed_card_checks_identity_and_expiry(){
        let key=p2p_key(&[2;32],true).unwrap();
        let (card,data)=sign(&key,&Publication{name:"Music".into(),topics:vec!["MUSIC".into(),"music".into()]},1000).unwrap();
        assert_eq!(card.topics,vec!["music"]);
        assert_eq!(verify(&data,key.public().to_peer_id(),1001).unwrap(),card);
        assert!(verify(&data,p2p_key(&[3;32],true).unwrap().public().to_peer_id(),1001).is_err());
        assert!(verify(&data,key.public().to_peer_id(),1000+TTL).is_err());
        let mut envelope:SignedCard=serde_json::from_slice(&data).unwrap();
        let first=if envelope.signature.starts_with("00"){"01"}else{"00"};envelope.signature.replace_range(..2,first);
        let changed=serde_json::to_vec(&envelope).unwrap();
        if changed!=data{assert!(verify(&changed,key.public().to_peer_id(),1001).is_err());}
    }
    #[test]fn index_tags_are_bounded(){assert!(topic("Private prompt text").is_err());assert_eq!(topic("MUSIC").unwrap(),"music");}
}
