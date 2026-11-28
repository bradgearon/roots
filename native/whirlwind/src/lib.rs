//! Internal compute tunnel. External mesh identities and routes are never loaded here.
//! An independently authenticated transport stream carries the existing pinned TLS
//! gateway. No automatic fallback after sending any HTTP request bytes.
use anyhow::{anyhow, bail, Result};
use futures::StreamExt;
use iroh::{Endpoint, EndpointAddr, SecretKey, PublicKey, endpoint::presets};
use libp2p::{identity, Multiaddr, PeerId, StreamProtocol, Swarm, SwarmBuilder, swarm::{NetworkBehaviour, SwarmEvent}, multiaddr::Protocol};
use sha2::{Digest, Sha256};
use std::{path::{Path,PathBuf}, sync::Arc, time::Duration};
use tokio::{io::{AsyncRead,AsyncWrite,AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream},sync::Semaphore,task::JoinHandle,time::timeout};
use tokio_util::compat::FuturesAsyncReadCompatExt;
use zeroize::Zeroize;

const ALPN: &[u8] = b"roots/internal/compute/1";
const P2P_PROTOCOL: StreamProtocol = StreamProtocol::new("/roots/internal/compute/1");
pub const EXTERNAL_KAD_PROTOCOL: StreamProtocol = StreamProtocol::new("/roots/external/kad/1");
const HELLO: &[u8; 4] = b"RIC1";
const OK: u8 = 1;
const DEADLINE: Duration = Duration::from_secs(100);
const SETUP: Duration = Duration::from_secs(18);
const GATEWAY: &str = "127.0.0.1:8221";

fn derived(seed: &[u8;32],label: &[u8])->[u8;32] {
    let mut h=Sha256::new();h.update(b"roots-whirlwind-key-v1\0");h.update(label);h.update(seed);h.finalize().into()
}
fn iroh_key(seed: &[u8;32])->SecretKey{
    let mut bytes=derived(seed,b"internal/iroh");
    let key=SecretKey::from_bytes(&bytes);bytes.zeroize();key
}
fn p2p_key(seed: &[u8;32],external: bool)->Result<identity::Keypair>{
    let mut bytes=derived(seed,if external {b"external/libp2p"}else{b"internal/libp2p"});
    let key=identity::Keypair::ed25519_from_bytes(&mut bytes).map_err(|_|anyhow!("invalid peer key"));
    bytes.zeroize();key
}
pub fn identities(seed: &[u8;32])->Result<(String,String)>{
    Ok((hex::encode(iroh_key(seed).public().as_bytes()),p2p_key(seed,false)?.public().to_peer_id().to_string()))
}
fn parse_iroh_id(text:&str)->Result<PublicKey>{
    let bytes:[u8;32]=hex::decode(text)?.try_into().map_err(|_|anyhow!("invalid endpoint id"))?;
    Ok(PublicKey::from_bytes(&bytes)?)
}
fn allowed(file:&Path,kind:&str,id:&str)->bool{
    let Ok(meta)=std::fs::metadata(file) else{return false};
    if meta.len()>65536{return false}
    let Ok(text)=std::fs::read_to_string(file) else{return false};
    text.lines().any(|line|{
        let mut parts=line.split_whitespace();
        parts.next()==Some(kind)&&parts.next()==Some(id)&&parts.next().is_none()
    })
}

#[derive(NetworkBehaviour)]
struct InternalBehaviour {
    streams: libp2p_stream::Behaviour,
    relay: libp2p::relay::client::Behaviour,
    dcutr: libp2p::dcutr::Behaviour,
    identify: libp2p::identify::Behaviour,
}
async fn swarm(seed:&[u8;32])->Result<Swarm<InternalBehaviour>>{
    let key=p2p_key(seed,false)?;
    let s=SwarmBuilder::with_existing_identity(key).with_tokio()
        .with_tcp(libp2p::tcp::Config::default(),libp2p::noise::Config::new,libp2p::yamux::Config::default)?
        .with_quic().with_dns()?
        .with_relay_client(libp2p::noise::Config::new,libp2p::yamux::Config::default)?
        .with_behaviour(|key,relay|InternalBehaviour{
            streams:libp2p_stream::Behaviour::new(),relay,
            dcutr:libp2p::dcutr::Behaviour::new(key.public().to_peer_id()),
            identify:libp2p::identify::Behaviour::new(libp2p::identify::Config::new("/roots/internal/identify/1".into(),key.public())),
        })?.with_swarm_config(|c|c.with_idle_connection_timeout(DEADLINE)).build();
    Ok(s)
}
async fn iroh_endpoint(seed:&[u8;32],server:bool)->Result<Endpoint>{
    // Keep default relay traversal, but disable public DNS/Pkarr publication
    // and lookup. Routes are conveyed privately as part of device pairing.
    let mut b=Endpoint::builder(presets::N0).clear_address_lookup().secret_key(iroh_key(seed));
    if server {b=b.alpns(vec![ALPN.to_vec()]);}
    Ok(b.bind().await?)
}

async fn bridge_iroh(mut local:TcpStream,mut send:iroh::endpoint::SendStream,mut recv:iroh::endpoint::RecvStream)->Result<()>{
    let (mut read,mut write)=local.split();
    let up=async {
        let mut buf=[0u8;16384];
        loop {let n=read.read(&mut buf).await?;if n==0{break}send.write_all(&buf[..n]).await?;}
        send.finish()?;Ok::<_,anyhow::Error>(())
    };
    let down=async{
        let mut buf=[0u8;16384];
        while let Some(n)=recv.read(&mut buf).await?{write.write_all(&buf[..n]).await?;}
        write.shutdown().await?;Ok::<_,anyhow::Error>(())
    };
    timeout(DEADLINE,async{tokio::try_join!(up,down)?;Ok::<_,anyhow::Error>(())}).await??;Ok(())
}
async fn bridge_p2p<T:AsyncRead+AsyncWrite+Unpin>(mut local:TcpStream,mut remote:T)->Result<()>{
    timeout(DEADLINE,tokio::io::copy_bidirectional(&mut local,&mut remote)).await??;Ok(())
}
async fn gateway()->Result<TcpStream>{
    Ok(timeout(Duration::from_secs(4),TcpStream::connect(GATEWAY)).await??)
}

async fn host_iroh(ep:Endpoint,grants:PathBuf,slots:Arc<Semaphore>){
    while let Some(incoming)=ep.accept().await{
        let Ok(permit)=slots.clone().try_acquire_owned() else{drop(incoming);continue};
        let grants=grants.clone();
        tokio::spawn(async move{
            let _permit=permit;
            let result:Result<()>=async{
                let conn=timeout(SETUP,async {incoming.await}).await??;
                let id=hex::encode(conn.remote_id().as_bytes());
                if !allowed(&grants,"iroh",&id){conn.close(1u32.into(),b"denied");bail!("not authorized")}
                let (mut send,mut recv)=timeout(SETUP,conn.accept_bi()).await??;
                let mut hello=[0;4];timeout(SETUP,recv.read_exact(&mut hello)).await??;
                if &hello!=HELLO{bail!("invalid compute stream")}
                let local=gateway().await?;
                send.write_all(&[OK]).await?;
                bridge_iroh(local,send,recv).await?;
                Ok(())
            }.await;
            // Never print remote identity, bearer, or content.
            if result.is_err(){eprintln!("Internal Iroh session rejected or interrupted");}
        });
    }
}

async fn host_p2p(seed:&[u8;32],grants:PathBuf,relay:Option<&str>,slots:Arc<Semaphore>)->Result<()>{
    let mut swarm=swarm(seed).await?;
    let id=*swarm.local_peer_id();
    for addr in ["/ip4/0.0.0.0/tcp/0","/ip6/::/tcp/0","/ip4/0.0.0.0/udp/0/quic-v1","/ip6/::/udp/0/quic-v1"]{
        if let Err(_) = swarm.listen_on(addr.parse()?){eprintln!("Optional libp2p listener unavailable");}
    }
    if let Some(relay)=relay{
        let address:Multiaddr=relay.parse()?;
        if !matches!(address.iter().last(),Some(Protocol::P2p(_))){bail!("relay must end with its peer id")}
        swarm.listen_on(address.with(Protocol::P2pCircuit))?;
    }
    let mut incoming=swarm.behaviour().streams.new_control().accept(P2P_PROTOCOL)?;
    loop {tokio::select!{
        event=swarm.select_next_some()=>{
            if let SwarmEvent::NewListenAddr{address,..}=event{
                println!("Internal libp2p route: {}",address.with(Protocol::P2p(id)));
            }
        }
        item=incoming.next()=>{
            let Some((peer,stream))=item else{break};
            if !allowed(&grants,"libp2p",&peer.to_string()){drop(stream);continue}
            let Ok(permit)=slots.clone().try_acquire_owned() else{drop(stream);continue};
            tokio::spawn(async move{
                let _permit=permit;
                let result:Result<()>=async{
                    let mut stream=stream.compat();
                    let mut hello=[0;4];timeout(SETUP,stream.read_exact(&mut hello)).await??;
                    if &hello!=HELLO{bail!("invalid compute stream")}
                    let local=gateway().await?;
                    stream.write_all(&[OK]).await?;stream.flush().await?;
                    bridge_p2p(local,stream).await
                }.await;
                if result.is_err(){eprintln!("Internal libp2p session interrupted");}
            });
        }
    }}
    Ok(())
}

pub async fn serve(seed:&[u8;32],grants:PathBuf,relay:Option<&str>)->Result<()>{
    let ep=iroh_endpoint(seed,true).await?;
    let (iroh_id,peer)=identities(seed)?;
    if timeout(Duration::from_secs(20),ep.online()).await.is_ok(){
        let address=ep.addr();
        for relay in address.relay_urls(){println!("Internal compute address: roots+iroh://{iroh_id}?relay={relay}");}
    }
    if ep.addr().relay_urls().next().is_none(){eprintln!("No Iroh relay route yet; use a configured libp2p fallback or retry host startup");}
    println!("Internal fallback peer: {peer}");
    println!("Only explicitly allowed devices can open the compute tunnel; the existing TLS pin and inference key remain required.");
    let slots=Arc::new(Semaphore::new(4));
    tokio::select!{
        _=host_iroh(ep.clone(),grants.clone(),slots.clone())=>bail!("Iroh listener stopped"),
        result=host_p2p(seed,grants,relay,slots)=>result?,
        result=tokio::signal::ctrl_c()=>result?,
    }
    ep.close().await;Ok(())
}

struct IrohPrepared {ep:Endpoint,conn:iroh::endpoint::Connection,send:iroh::endpoint::SendStream,recv:iroh::endpoint::RecvStream}
async fn prepare_iroh(seed:&[u8;32],target:&str,relay:&str)->Result<IrohPrepared>{
    let id=parse_iroh_id(target)?;
    if relay.is_empty(){bail!("Private relay route missing")}
    let address=EndpointAddr::new(id).with_relay_url(relay.parse()?);
    let ep=iroh_endpoint(seed,false).await?;
    let conn=ep.connect(address,ALPN).await?;
    let (mut send,mut recv)=conn.open_bi().await?;
    send.write_all(HELLO).await?;
    let mut ack=[0];recv.read_exact(&mut ack).await?;
    if ack[0]!=OK{bail!("remote compute unavailable or not authorized")}
    Ok(IrohPrepared{ep,conn,send,recv})
}
struct P2pPrepared {stream:libp2p::swarm::Stream,driver:JoinHandle<()>}
impl Drop for P2pPrepared{fn drop(&mut self){self.driver.abort();}}
async fn prepare_p2p(seed:&[u8;32],target:&str)->Result<P2pPrepared>{
    let addr:Multiaddr=target.parse()?;
    let Some(Protocol::P2p(peer))=addr.iter().last() else{bail!("fallback requires a pinned peer id")};
    let mut swarm=swarm(seed).await?;
    let mut control=swarm.behaviour().streams.new_control();
    swarm.dial(addr)?;
    loop{
        match swarm.select_next_some().await{
            SwarmEvent::ConnectionEstablished{peer_id,..} if peer_id==peer=>break,
            SwarmEvent::OutgoingConnectionError{peer_id:Some(id),..} if id==peer=>bail!("fallback dial failed"),
            _=>{}
        }
    }
    // Drive the swarm during protocol negotiation and data transfer. Abort
    // it even if cancellation happens while the readiness proof is pending.
    struct Guard(Option<JoinHandle<()>>);
    impl Drop for Guard{fn drop(&mut self){if let Some(h)=self.0.take(){h.abort()}}}
    let mut guard=Guard(Some(tokio::spawn(async move{loop{let _=swarm.select_next_some().await;}})));
    let mut stream=control.open_stream(peer,P2P_PROTOCOL).await?.compat();
    stream.write_all(HELLO).await?;stream.flush().await?;
    let mut ack=[0];stream.read_exact(&mut ack).await?;
    if ack[0]!=OK{bail!("fallback not authorized")}
    Ok(P2pPrepared{stream:stream.into_inner(),driver:guard.0.take().unwrap()})
}
enum Prepared {Iroh(IrohPrepared),P2p(P2pPrepared)}
pub struct Tunnel {pub port:u16,pub transport:&'static str,pub task:JoinHandle<()>}
pub async fn tunnel(seed:&[u8;32],target:&str,relay:&str,fallback:&str)->Result<Tunnel>{
    // Fallback is strictly pre-request: the local listener is created only
    // after the chosen remote transport has accepted the capability stream.
    let prepared=match timeout(SETUP,prepare_iroh(seed,target,relay)).await{
        Ok(Ok(path))=>Prepared::Iroh(path),
        _ if !fallback.is_empty()=>Prepared::P2p(timeout(SETUP,prepare_p2p(seed,fallback)).await??),
        _=>bail!("Iroh unavailable or device not authorized"),
    };
    let transport=match &prepared{Prepared::Iroh(_)=>"iroh",Prepared::P2p(_)=>"libp2p"};
    let listener=TcpListener::bind("127.0.0.1:0").await?;
    let port=listener.local_addr()?.port();
    let task=tokio::spawn(async move{
        if let Ok(Ok((local,_)))=timeout(Duration::from_secs(10),listener.accept()).await{
            drop(listener);
            match prepared{
                Prepared::Iroh(path)=>{
                    let _=bridge_iroh(local,path.send,path.recv).await;
                    path.conn.close(0u32.into(),b"done");
                    path.ep.close().await;
                }
                Prepared::P2p(mut path)=>{
                    let _=bridge_p2p(local,(&mut path.stream).compat()).await;
                }
            }
        }
    });
    Ok(Tunnel{port,transport,task})
}

pub mod directory;
pub async fn external_dht(seed:&[u8;32],seeds:Vec<Multiaddr>)->Result<()>{
    directory::serve(seed,seeds,None).await
}
#[cfg(target_os="android")]
mod android;

#[cfg(test)]
mod tests{
    use super::*;
    #[test]fn identities_are_stable_and_mesh_scoped(){
        let s=[7u8;32];assert_eq!(identities(&s).unwrap(),identities(&s).unwrap());
        assert_ne!(identities(&s).unwrap().1,p2p_key(&s,true).unwrap().public().to_peer_id().to_string());
        assert_ne!(identities(&s).unwrap(),identities(&[8u8;32]).unwrap());
    }
    #[test]fn public_id_roundtrip(){
        let (id,_)=identities(&[3u8;32]).unwrap();assert_eq!(hex::encode(parse_iroh_id(&id).unwrap().as_bytes()),id);
    }
    #[test]fn missing_grants_fail_closed(){assert!(!allowed(Path::new("nonexistent-roots-grant-file"),"iroh","anything"));}
}
